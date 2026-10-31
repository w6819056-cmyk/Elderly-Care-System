package com.smartelder.api.tenant.auth;

import com.smartelder.core.iam.TenantAccessResolver;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TenantStaffAuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5uY5r5Jf5k5v5W5m5K5Q5n5u5p5e5a5";

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TenantStaffTokenService tokenService;
    private final TenantAccessResolver accessResolver;

    public TenantStaffAuthService(
            JdbcClient jdbc, PasswordEncoder passwordEncoder, TenantStaffTokenService tokenService,
            TenantAccessResolver accessResolver) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.accessResolver = accessResolver;
    }

    public LoginResult login(String account, String password) {
        if (account == null || account.isBlank() || password == null || password.isEmpty()) {
            throw invalidCredentials();
        }
        StaffRow staff = jdbc.sql("SELECT id, public_id, username, password_hash, staff_type, home_store_id, status "
                        + "FROM iam_staff WHERE username = :account OR phone = :account ORDER BY id LIMIT 1")
                .param("account", account.trim())
                .query((rs, row) -> new StaffRow(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("username"), rs.getString("password_hash"), rs.getString("staff_type"),
                        rs.getObject("home_store_id", Long.class), rs.getString("status")))
                .optional().orElse(null);
        if (staff == null) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw invalidCredentials();
        }
        if (!"ACTIVE".equals(staff.status()) || !passwordEncoder.matches(password, staff.passwordHash())) {
            throw invalidCredentials();
        }
        TenantStaffPrincipal principal = principal(staff);
        Instant issuedAt = Instant.now();
        String accessToken = tokenService.issue(staff.publicId(), issuedAt);
        Instant expiresAt = tokenService.parse(accessToken, issuedAt).expiresAt();
        return new LoginResult(accessToken, expiresAt, principal);
    }

    public TenantStaffPrincipal resolveToken(String token) {
        TenantStaffTokenService.ParsedToken parsed = tokenService.parse(token, Instant.now());
        StaffRow staff = jdbc.sql("SELECT id, public_id, username, password_hash, staff_type, home_store_id, status "
                        + "FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", parsed.subject())
                .query((rs, row) -> new StaffRow(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("username"), rs.getString("password_hash"), rs.getString("staff_type"),
                        rs.getObject("home_store_id", Long.class), rs.getString("status")))
                .optional().orElseThrow(TenantStaffAuthService::invalidToken);
        if (!"ACTIVE".equals(staff.status())) throw invalidToken();
        return principal(staff);
    }

    private TenantStaffPrincipal principal(StaffRow staff) {
        if (!"HQ".equals(staff.staffType()) && !"STORE".equals(staff.staffType())) throw invalidToken();
        if ("STORE".equals(staff.staffType()) && staff.homeStoreId() == null) throw invalidToken();
        if ("HQ".equals(staff.staffType()) && staff.homeStoreId() != null) throw invalidToken();
        return new TenantStaffPrincipal(staff.id(), staff.publicId(), staff.username(), staff.staffType(),
                staff.homeStoreId(), accessResolver.resolve(staff.id()));
    }

    private static TenantStaffAuthException invalidCredentials() {
        return new TenantStaffAuthException(401, "UNAUTHORIZED", "username or password is incorrect");
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("invalid or expired tenant staff token");
    }

    private record StaffRow(long id, String publicId, String username, String passwordHash,
                             String staffType, Long homeStoreId, String status) {}

    public record LoginResult(String accessToken, Instant expiresAt, TenantStaffPrincipal principal) {}
}
