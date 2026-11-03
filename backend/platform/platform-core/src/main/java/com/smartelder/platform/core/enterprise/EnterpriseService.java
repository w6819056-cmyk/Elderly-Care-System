package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public class EnterpriseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;

    public EnterpriseService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    public EnterpriseView create(
            CreateEnterpriseCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        Instant now = clock.now();
        String publicId = PublicIds.newId("ent");
        String requestedOrgNo = command.orgNo() == null ? "" : command.orgNo().trim();
        String orgNo = requestedOrgNo.isBlank() ? generatedOrgNo() : requestedOrgNo;
        if (jdbcClient.sql("SELECT COUNT(*) FROM pf_enterprise WHERE org_no = :orgNo")
                .param("orgNo", orgNo).query(Long.class).single() > 0) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "企业编号已登记");
        }
        try {
            jdbcClient.sql("""
                        INSERT INTO pf_enterprise(
                            public_id, org_no, name, contact_name, contact_phone,
                            status, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :orgNo, :name, :contactName, :contactPhone,
                            'ACTIVE', :createdAt, :updatedAt, 0
                        )
                        """)
                .param("publicId", publicId)
                .param("orgNo", orgNo)
                .param("name", command.name().trim())
                .param("contactName", command.contactName().trim())
                .param("contactPhone", command.contactPhone().trim())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "企业编号已登记", exception);
        }

        auditService.record(
                actor.userId(),
                actor.publicId(),
                "PLATFORM_ENTERPRISE_CREATED",
                "ENTERPRISE",
                publicId,
                "{\"orgNo\":\"" + orgNo + "\"}",
                traceId
        );
        return findByPublicId(publicId);
    }

    public PageResult<EnterpriseView> list(int page, int pageSize, String statusValue) {
        validatePaging(page, pageSize);
        EnterpriseStatus status = statusValue == null || statusValue.isBlank()
                ? null
                : parseStatus(statusValue);
        String where = status == null ? "" : "WHERE status = :status";
        String sql = """
                SELECT public_id, org_no, name, contact_name, contact_phone,
                       status, created_at, updated_at, version
                FROM pf_enterprise
                """ + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset";
        var statement = jdbcClient.sql(sql)
                .param("limit", pageSize)
                .param("offset", (page - 1) * pageSize);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        List<EnterpriseView> items = statement
                .query((rs, rowNum) -> mapRow(rs))
                .list();
        String countSql = "SELECT COUNT(*) FROM pf_enterprise "
                + (status == null ? "" : "WHERE status = :status");
        var countStatement = jdbcClient.sql(countSql);
        if (status != null) {
            countStatement = countStatement.param("status", status.name());
        }
        long total = countStatement.query(Long.class).single();
        return new PageResult<>(items, total, page, pageSize);
    }

    public EnterpriseView changeStatus(
            String publicId,
            String targetValue,
            PlatformPrincipal actor,
            String traceId
    ) {
        EnterpriseStatus target = parseStatus(targetValue);
        EnterpriseView current = findByPublicId(publicId);
        EnterpriseStatus source = EnterpriseStatus.valueOf(current.status());
        if (!source.canTransitionTo(target)) {
            throw new PlatformApiException(
                    ErrorCode.ENTERPRISE_STATE_CONFLICT,
                    "企业状态不能从 " + source + " 变更为 " + target
            );
        }
        Instant now = clock.now();
        int updated = jdbcClient.sql("""
                        UPDATE pf_enterprise
                        SET status = :status, updated_at = :updatedAt, version = version + 1
                        WHERE public_id = :publicId AND status = :currentStatus
                        """)
                .param("status", target.name())
                .param("updatedAt", Timestamp.from(now))
                .param("publicId", publicId)
                .param("currentStatus", source.name())
                .update();
        if (updated != 1) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "企业状态已被其他操作修改");
        }
        auditService.record(
                actor.userId(),
                actor.publicId(),
                "PLATFORM_ENTERPRISE_STATUS_CHANGED",
                "ENTERPRISE",
                publicId,
                "{\"from\":\"" + source + "\",\"to\":\"" + target + "\"}",
                traceId
        );
        return findByPublicId(publicId);
    }

    public EnterpriseView updateProfile(String publicId, UpdateEnterpriseCommand command, PlatformPrincipal actor, String traceId) {
        EnterpriseView current = findByPublicId(publicId);
        String name = command.name() == null ? "" : command.name().trim();
        String contactName = command.contactName() == null ? "" : command.contactName().trim();
        String contactPhone = command.contactPhone() == null ? "" : command.contactPhone().trim();
        if (name.isEmpty() || name.length() > 200 || contactName.isEmpty() || contactName.length() > 100 || contactPhone.isEmpty() || contactPhone.length() > 40) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "企业名称、联系人和联系电话均不能为空且长度有效");
        }
        int updated = jdbcClient.sql("UPDATE pf_enterprise SET name=:name,contact_name=:contactName,contact_phone=:contactPhone,updated_at=:updatedAt,version=version+1 WHERE public_id=:publicId AND version=:version")
                .param("name", name).param("contactName", contactName).param("contactPhone", contactPhone)
                .param("updatedAt", Timestamp.from(clock.now())).param("publicId", publicId)
                .param("version", current.version()).update();
        if (updated != 1) throw new PlatformApiException(ErrorCode.CONFLICT, "企业资料已被其他操作修改");
        auditService.record(actor.userId(), actor.publicId(), "PLATFORM_ENTERPRISE_PROFILE_UPDATED", "ENTERPRISE", publicId, "{\"fields\":[\"name\",\"contactName\",\"contactPhone\"]}", traceId);
        return findByPublicId(publicId);
    }

    public EnterpriseView findByPublicId(String publicId) {
        return jdbcClient.sql("""
                        SELECT public_id, org_no, name, contact_name, contact_phone,
                               status, created_at, updated_at, version
                        FROM pf_enterprise
                        WHERE public_id = :publicId
                        """)
                .param("publicId", publicId)
                .query((rs, rowNum) -> mapRow(rs))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.NOT_FOUND,
                        "企业不存在"
                ));
    }

    public EnterpriseStatus requireStatus(long enterpriseId) {
        String status = jdbcClient.sql("""
                        SELECT status FROM pf_enterprise WHERE id = :enterpriseId
                        """)
                .param("enterpriseId", enterpriseId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.NOT_FOUND,
                        "企业不存在"
                ));
        return EnterpriseStatus.valueOf(status);
    }

    private static EnterpriseView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EnterpriseView(
                rs.getString("public_id"),
                rs.getString("org_no"),
                rs.getString("name"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")
        );
    }

    private static String generatedOrgNo() {
        return "ORG" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }

    private static EnterpriseStatus parseStatus(String value) {
        try {
            return EnterpriseStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "无效的企业状态");
        }
    }

    private static void validatePaging(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "page 必须大于 0，pageSize 必须介于 1 和 " + MAX_PAGE_SIZE + " 之间"
            );
        }
    }

    public record CreateEnterpriseCommand(
            String name,
            String orgNo,
            String contactName,
            String contactPhone
    ) {
    }

    public record UpdateEnterpriseCommand(String name, String contactName, String contactPhone) {}

    public record EnterpriseView(
            String publicId,
            String orgNo,
            String name,
            String contactName,
            String contactPhone,
            String status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
    }
}
