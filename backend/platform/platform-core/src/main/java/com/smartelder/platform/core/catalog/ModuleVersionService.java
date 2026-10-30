package com.smartelder.platform.core.catalog;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ModuleVersionService {

    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    );
    private static final int MAX_VERSION_LENGTH = 30;

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ModuleVersionService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public VersionView addVersion(
            String moduleCode,
            CreateVersionCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        String version = requireValidVersion(
                command.version(),
                ErrorCode.MODULE_VERSION_INVALID,
                "version"
        );
        String minimumCoreVersion = requireValidVersion(
                command.minCoreVersion(),
                ErrorCode.MODULE_VERSION_INVALID,
                "minCoreVersion"
        );
        Instant now = clock.now();
        return transactionTemplate.execute(status -> {
            ModuleRef module = findModuleForUpdate(moduleCode);
            try {
                jdbcClient.sql("""
                                INSERT INTO pf_module_version(
                                    module_id, version, min_core_version, created_at
                                ) VALUES (
                                    :moduleId, :version, :minimumCoreVersion, :createdAt
                                )
                                """)
                        .param("moduleId", module.id())
                        .param("version", version)
                        .param("minimumCoreVersion", minimumCoreVersion)
                        .param("createdAt", Timestamp.from(now))
                        .update();
            } catch (DuplicateKeyException exception) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_VERSION_CONFLICT,
                        "模块 " + module.code() + " 的版本 " + version + " 已存在",
                        exception
                );
            }
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "PLATFORM_MODULE_VERSION_ADDED",
                    "MODULE",
                    module.code(),
                    "{\"version\":\"" + version + "\"}",
                    traceId
            );
            return new VersionView(version, minimumCoreVersion, now);
        });
    }

    public List<VersionView> listVersions(long moduleId) {
        return jdbcClient.sql("""
                        SELECT version, min_core_version, created_at
                        FROM pf_module_version
                        WHERE module_id = :moduleId
                        ORDER BY id ASC
                        """)
                .param("moduleId", moduleId)
                .query((rs, rowNum) -> new VersionView(
                        rs.getString("version"),
                        rs.getString("min_core_version"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public Optional<String> highestVersion(List<VersionView> versions) {
        return versions.stream()
                .max(Comparator.comparing(version -> SemanticVersion.parse(version.version())))
                .map(VersionView::version);
    }

    public static String requireValidVersion(
            String value,
            ErrorCode errorCode,
            String fieldName
    ) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_VERSION_LENGTH
                || !SEMANTIC_VERSION.matcher(normalized).matches()
                || !validPrerelease(normalized)) {
            throw new PlatformApiException(
                    errorCode,
                    fieldName + " 必须是 major.minor.patch 格式的语义化版本"
            );
        }
        return normalized;
    }

    private static boolean validPrerelease(String value) {
        Matcher matcher = SEMANTIC_VERSION.matcher(value);
        if (!matcher.matches() || matcher.group(4) == null) {
            return true;
        }
        for (String identifier : matcher.group(4).split("\\.")) {
            if (identifier.length() > 1
                    && identifier.charAt(0) == '0'
                    && identifier.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private ModuleRef findModuleForUpdate(String code) {
        String normalizedCode = code == null ? "" : code.trim();
        return jdbcClient.sql("""
                        SELECT id, code
                        FROM pf_module_definition
                        WHERE code = :code
                        FOR UPDATE
                        """)
                .param("code", normalizedCode)
                .query((rs, rowNum) -> new ModuleRef(rs.getLong("id"), rs.getString("code")))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.MODULE_NOT_FOUND,
                        "模块 " + normalizedCode + " 不存在"
                ));
    }

    private record ModuleRef(long id, String code) {
    }

    public record CreateVersionCommand(String version, String minCoreVersion) {
    }

    public record VersionView(String version, String minCoreVersion, Instant createdAt) {
    }

    private record SemanticVersion(
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            List<String> prerelease,
            String original
    ) implements Comparable<SemanticVersion> {

        private static SemanticVersion parse(String value) {
            Matcher matcher = SEMANTIC_VERSION.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid semantic version: " + value);
            }
            List<String> prerelease = matcher.group(4) == null
                    ? List.of()
                    : List.of(matcher.group(4).split("\\."));
            return new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    prerelease,
                    value
            );
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int compared = major.compareTo(other.major);
            if (compared != 0) {
                return compared;
            }
            compared = minor.compareTo(other.minor);
            if (compared != 0) {
                return compared;
            }
            compared = patch.compareTo(other.patch);
            if (compared != 0) {
                return compared;
            }
            if (prerelease.isEmpty() && other.prerelease.isEmpty()) {
                return 0;
            }
            if (prerelease.isEmpty()) {
                return 1;
            }
            if (other.prerelease.isEmpty()) {
                return -1;
            }
            int count = Math.min(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < count; index++) {
                compared = compareIdentifier(prerelease.get(index), other.prerelease.get(index));
                if (compared != 0) {
                    return compared;
                }
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        private static int compareIdentifier(String left, String right) {
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric) {
                return -1;
            }
            if (rightNumeric) {
                return 1;
            }
            return left.compareTo(right);
        }
    }
}
