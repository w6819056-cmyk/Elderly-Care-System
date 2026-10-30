package com.smartelder.platform.core.auth;

import com.smartelder.platform.core.common.PublicIds;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class PlatformAdminRepository {

    private static final String SUPER_ADMIN_ROLE_CODE = "PLATFORM_SUPER_ADMIN";

    private final JdbcClient jdbcClient;

    public PlatformAdminRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<PlatformAdminAccount> findByUsername(String username) {
        return jdbcClient.sql("""
                        SELECT id, public_id, username, password_hash, display_name,
                               status, failed_login_count, locked_until
                        FROM pf_admin_user
                        WHERE username = :username
                        """)
                .param("username", username)
                .query((rs, rowNum) -> new PlatformAdminAccount(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getInt("failed_login_count"),
                        toInstant(rs.getTimestamp("locked_until"))
                ))
                .optional();
    }

    public Optional<PlatformAdminAccount> findById(long userId) {
        return jdbcClient.sql("""
                        SELECT id, public_id, username, password_hash, display_name,
                               status, failed_login_count, locked_until
                        FROM pf_admin_user
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new PlatformAdminAccount(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getInt("failed_login_count"),
                        toInstant(rs.getTimestamp("locked_until"))
                ))
                .optional();
    }

    public Set<String> findRoleCodes(long userId) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT r.code
                        FROM pf_admin_user_role ur
                        JOIN pf_admin_role r ON r.id = ur.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list());
    }

    public Set<String> findPermissionCodes(long userId) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT DISTINCT p.code
                        FROM pf_admin_user_role ur
                        JOIN pf_admin_role_permission rp ON rp.role_id = ur.role_id
                        JOIN pf_admin_permission p ON p.id = rp.permission_id
                        WHERE ur.user_id = :userId
                        ORDER BY p.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list());
    }

    public void recordFailedLogin(
            long userId,
            int nextFailureCount,
            Instant now,
            Instant lockedUntil
    ) {
        jdbcClient.sql("""
                        UPDATE pf_admin_user
                        SET failed_login_count = :nextFailureCount,
                            locked_until = :lockedUntil,
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :userId
                        """)
                .param("nextFailureCount", nextFailureCount)
                .param("lockedUntil", lockedUntil == null ? null : Timestamp.from(lockedUntil))
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
    }

    public void resetLoginFailures(long userId, Instant now) {
        jdbcClient.sql("""
                        UPDATE pf_admin_user
                        SET failed_login_count = 0,
                            locked_until = NULL,
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :userId
                        """)
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
    }

    public boolean hasAnyPlatformAdministrator() {
        return jdbcClient.sql("SELECT COUNT(*) FROM pf_admin_user")
                .query(Long.class)
                .single() > 0;
    }

    @Transactional
    public void createBootstrapAdministrator(
            String username,
            String passwordHash,
            String displayName,
            Instant now
    ) {
        if (hasAnyPlatformAdministrator()) {
            return;
        }

        long roleId = ensureSuperAdminRole(now);
        String adminPublicId = PublicIds.newId("adm");
        jdbcClient.sql("""
                        INSERT INTO pf_admin_user(
                            public_id, username, password_hash, display_name, status,
                            failed_login_count, locked_until, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :username, :passwordHash, :displayName, 'ACTIVE',
                            0, NULL, :now, :now, 0
                        )
                        """)
                .param("publicId", adminPublicId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("displayName", displayName)
                .param("now", Timestamp.from(now))
                .update();

        long adminId = jdbcClient.sql("SELECT id FROM pf_admin_user WHERE username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("INSERT INTO pf_admin_user_role(user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", adminId)
                .param("roleId", roleId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO pf_admin_role_permission(role_id, permission_id)
                        SELECT :roleId, id FROM pf_admin_permission
                        WHERE NOT EXISTS (
                            SELECT 1 FROM pf_admin_role_permission rp
                            WHERE rp.role_id = :roleId AND rp.permission_id = pf_admin_permission.id
                        )
                        """)
                .param("roleId", roleId)
                .update();
    }

    private long ensureSuperAdminRole(Instant now) {
        Optional<Long> existing = jdbcClient.sql("""
                        SELECT id FROM pf_admin_role WHERE code = :code
                        """)
                .param("code", SUPER_ADMIN_ROLE_CODE)
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        jdbcClient.sql("""
                        INSERT INTO pf_admin_role(
                            public_id, code, name, built_in, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :code, '平台超级管理员', TRUE, :now, :now, 0
                        )
                        """)
                .param("publicId", PublicIds.newId("rol"))
                .param("code", SUPER_ADMIN_ROLE_CODE)
                .param("now", Timestamp.from(now))
                .update();
        return jdbcClient.sql("SELECT id FROM pf_admin_role WHERE code = :code")
                .param("code", SUPER_ADMIN_ROLE_CODE)
                .query(Long.class)
                .single();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record PlatformAdminAccount(
            long id,
            String publicId,
            String username,
            String passwordHash,
            String displayName,
            String status,
            int failedLoginCount,
            Instant lockedUntil
    ) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }
}

