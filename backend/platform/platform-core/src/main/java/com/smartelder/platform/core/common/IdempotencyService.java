package com.smartelder.platform.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofDays(30);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final PlatformClock clock;

    public IdempotencyService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PlatformClock clock
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public <T> T execute(
            String scope,
            String key,
            String method,
            String path,
            String requestBody,
            Supplier<T> action,
            Class<T> resultType
    ) {
        String normalizedScope = requireText(scope, "scope", 100);
        String normalizedKey = requireText(key, "idempotency key", 100);
        String normalizedMethod = requireText(method, "method", 10).toUpperCase(Locale.ROOT);
        String normalizedPath = requireText(path, "path", 300);
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");

        String body = requestBody == null ? "" : requestBody;
        String requestHash = sha256(body);
        Instant now = clock.now();

        try {
            return transactionTemplate.execute(status -> {
                deleteExpired(normalizedScope, normalizedKey, now);
                try {
                    insertClaim(normalizedScope, normalizedKey, normalizedMethod, normalizedPath, requestHash, now);
                } catch (DataIntegrityViolationException exception) {
                    throw new DuplicateIdempotencyKeyException(exception);
                }

                T result = action.get();
                int updated = jdbcClient.sql("""
                                UPDATE pf_idempotency_record
                                SET status = 'COMPLETED', response_json = :responseJson
                                WHERE scope = :scope
                                  AND idempotency_key = :idempotencyKey
                                  AND status = 'IN_PROGRESS'
                                """)
                        .param("responseJson", serialize(result), Types.VARCHAR)
                        .param("scope", normalizedScope)
                        .param("idempotencyKey", normalizedKey)
                        .update();
                if (updated != 1) {
                    throw new IllegalStateException("failed to complete idempotency record");
                }
                return result;
            });
        } catch (DuplicateIdempotencyKeyException exception) {
            return loadExisting(
                    normalizedScope,
                    normalizedKey,
                    normalizedMethod,
                    normalizedPath,
                    requestHash,
                    resultType
            );
        }
    }

    private void insertClaim(
            String scope,
            String key,
            String method,
            String path,
            String requestHash,
            Instant now
    ) {
        jdbcClient.sql("""
                        INSERT INTO pf_idempotency_record(
                            scope, idempotency_key, method, path, request_hash,
                            status, response_json, expires_at, created_at
                        ) VALUES (
                            :scope, :idempotencyKey, :method, :path, :requestHash,
                            'IN_PROGRESS', NULL, :expiresAt, :createdAt
                        )
                        """)
                .param("scope", scope)
                .param("idempotencyKey", key)
                .param("method", method)
                .param("path", path)
                .param("requestHash", requestHash)
                .param("expiresAt", Timestamp.from(now.plus(RETENTION)))
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    private void deleteExpired(String scope, String key, Instant now) {
        jdbcClient.sql("""
                        DELETE FROM pf_idempotency_record
                        WHERE scope = :scope
                          AND idempotency_key = :idempotencyKey
                          AND expires_at < :now
                        """)
                .param("scope", scope)
                .param("idempotencyKey", key)
                .param("now", Timestamp.from(now))
                .update();
    }

    private <T> T loadExisting(
            String scope,
            String key,
            String method,
            String path,
            String requestHash,
            Class<T> resultType
    ) {
        StoredRecord stored = jdbcClient.sql("""
                        SELECT method, path, request_hash, status, response_json
                        FROM pf_idempotency_record
                        WHERE scope = :scope AND idempotency_key = :idempotencyKey
                        """)
                .param("scope", scope)
                .param("idempotencyKey", key)
                .query((rs, rowNum) -> new StoredRecord(
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getString("response_json")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.CONFLICT,
                        "幂等记录正在处理中，请稍后重试"
                ));

        boolean sameRequest = method.equalsIgnoreCase(stored.method())
                && path.equals(stored.path())
                && requestHash.equals(stored.requestHash());
        if (!sameRequest) {
            throw new PlatformApiException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "幂等键已被其他请求使用，请为新请求生成新的幂等键"
            );
        }
        if (!"COMPLETED".equals(stored.status()) || stored.responseJson() == null) {
            throw new PlatformApiException(
                    ErrorCode.CONFLICT,
                    "幂等请求尚未完成，请稍后重试"
            );
        }

        try {
            return objectMapper.readValue(stored.responseJson(), resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize idempotent response", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize idempotent response", exception);
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record StoredRecord(
            String method,
            String path,
            String requestHash,
            String status,
            String responseJson
    ) {
    }

    private static final class DuplicateIdempotencyKeyException extends RuntimeException {
        private DuplicateIdempotencyKeyException(Throwable cause) {
            super(cause);
        }
    }
}
