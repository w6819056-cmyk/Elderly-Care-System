package com.smartelder.platform.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PlatformTestDatabase;
import com.smartelder.platform.core.common.PublicIds;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlatformAuthServiceTest {

    private static final String SECRET = "platform-test-secret-at-least-32-bytes-long";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "ChangeMe-Platform-2026";

    private PlatformTestDatabase database;
    private MutableClock clock;
    private PlatformAdminRepository repository;
    private PlatformAuthService service;
    private PlatformTokenService tokenService;
    private PasswordEncoder passwordEncoder;
    private long userId;

    @BeforeEach
    void setUp() {
        database = PlatformTestDatabase.create("platform_auth");
        clock = new MutableClock(Instant.parse("2026-10-30T00:00:00Z"));
        var platformClock = new PlatformClock(clock);
        passwordEncoder = new BCryptPasswordEncoder(4);
        repository = new PlatformAdminRepository(database.jdbcClient());
        tokenService = new PlatformTokenService(SECRET, Duration.ofHours(8));
        service = new PlatformAuthService(
                repository,
                passwordEncoder,
                tokenService,
                new PlatformAuditService(database.jdbcClient(), platformClock),
                platformClock
        );
        userId = insertAdmin();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void rejectsWrongPasswordAndLocksAfterFiveFailures() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> service.login(USERNAME, "wrong-password"))
                    .isInstanceOfSatisfying(PlatformApiException.class,
                            error -> assertThat(error.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }

        assertThatThrownBy(() -> service.login(USERNAME, "wrong-password"))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.UNAUTHORIZED));

        var row = database.jdbcClient().sql("""
                        SELECT failed_login_count, locked_until
                        FROM pf_admin_user WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getInt("failed_login_count"),
                        rs.getTimestamp("locked_until").toInstant()
                })
                .single();
        assertThat(row[0]).isEqualTo(5);
        assertThat(row[1]).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> service.login(USERNAME, PASSWORD))
                .isInstanceOf(PlatformApiException.class);

        clock.advance(Duration.ofMinutes(15));
        PlatformAuthService.LoginResult result = service.login(USERNAME, PASSWORD);
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.principal().username()).isEqualTo(USERNAME);

        Integer remainingFailures = database.jdbcClient()
                .sql("SELECT failed_login_count FROM pf_admin_user WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
        assertThat(remainingFailures).isZero();
    }

    @Test
    void loginLoadsRolesAndPermissionsFromDatabase() {
        PlatformAuthService.LoginResult result = service.login(USERNAME, PASSWORD);

        assertThat(result.principal().roleCodes()).containsExactly("PLATFORM_SUPER_ADMIN");
        assertThat(result.principal().permissionCodes())
                .contains("platform.enterprise.manage", "platform.module.manage", "platform.audit.view")
                .hasSize(13);
        assertThat(result.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(8)));
        assertThat(tokenService.parse(result.accessToken()).permissionCodes())
                .containsExactlyInAnyOrderElementsOf(result.principal().permissionCodes());
    }

    @Test
    void tokenAudienceMustBePlatform() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tenantToken = Jwts.builder()
                .subject("adm_other")
                .audience().add("TENANT_STAFF").and()
                .claim("uid", userId)
                .claim("username", USERNAME)
                .claim("roles", Set.of("PLATFORM_SUPER_ADMIN"))
                .claim("permissions", Set.of("platform.enterprise.manage"))
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofHours(1))))
                .id(PublicIds.newId("adm"))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> tokenService.parse(tenantToken))
                .isInstanceOf(PlatformApiException.class)
                .extracting(error -> ((PlatformApiException) error).code())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsValidTokenAfterAccountIsDisabled() {
        String token = service.login(USERNAME, PASSWORD).accessToken();
        database.jdbcClient().sql("UPDATE pf_admin_user SET status = 'DISABLED' WHERE id = :id")
                .param("id", userId)
                .update();

        assertThatThrownBy(() -> service.resolveToken(token))
                .isInstanceOfSatisfying(PlatformApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    private long insertAdmin() {
        String hash = passwordEncoder.encode(PASSWORD);
        database.jdbcClient().sql("""
                        INSERT INTO pf_admin_user(
                            public_id, username, password_hash, display_name, status,
                            failed_login_count, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :username, :passwordHash, '平台管理员', 'ACTIVE',
                            0, :now, :now, 0
                        )
                        """)
                .param("publicId", PublicIds.newId("adm"))
                .param("username", USERNAME)
                .param("passwordHash", hash)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
        long id = database.jdbcClient()
                .sql("SELECT id FROM pf_admin_user WHERE username = :username")
                .param("username", USERNAME)
                .query(Long.class)
                .single();
        database.jdbcClient().sql("""
                        INSERT INTO pf_admin_role(public_id, code, name, built_in, created_at, updated_at, version)
                        VALUES (:publicId, 'PLATFORM_SUPER_ADMIN', '平台超级管理员', TRUE, :now, :now, 0)
                        """)
                .param("publicId", PublicIds.newId("adm"))
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
        long roleId = database.jdbcClient()
                .sql("SELECT id FROM pf_admin_role WHERE code = 'PLATFORM_SUPER_ADMIN'")
                .query(Long.class)
                .single();
        database.jdbcClient().sql("INSERT INTO pf_admin_user_role(user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", id)
                .param("roleId", roleId)
                .update();
        database.jdbcClient().sql("""
                        INSERT INTO pf_admin_role_permission(role_id, permission_id)
                        SELECT :roleId, id FROM pf_admin_permission
                        """)
                .param("roleId", roleId)
                .update();
        return id;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

