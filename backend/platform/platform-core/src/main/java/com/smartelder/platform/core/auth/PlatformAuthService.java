package com.smartelder.platform.core.auth;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformAdminRepository.PlatformAdminAccount;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;

public class PlatformAuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5uY5r5Jf5k5v5W5m5K5Q5n5u5p5e5a5";

    private final PlatformAdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTokenService tokenService;
    private final PlatformAuditService auditService;
    private final PlatformClock clock;

    public PlatformAuthService(
            PlatformAdminRepository repository,
            PasswordEncoder passwordEncoder,
            PlatformTokenService tokenService,
            PlatformAuditService auditService,
            PlatformClock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new PlatformApiException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        Instant now = clock.now();
        PlatformAdminAccount account = repository.findByUsername(username.trim()).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw new PlatformApiException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        if (!account.active() || account.lockedAt(now)) {
            throw new PlatformApiException(ErrorCode.UNAUTHORIZED, "账号不可用或已临时锁定");
        }

        if (!passwordEncoder.matches(password, account.passwordHash())) {
            int nextFailureCount = account.failedLoginCount() + 1;
            Instant lockedUntil = nextFailureCount >= MAX_FAILED_ATTEMPTS
                    ? now.plus(LOCK_DURATION)
                    : account.lockedUntil();
            repository.recordFailedLogin(account.id(), nextFailureCount, now, lockedUntil);
            auditService.record(
                    account.id(),
                    account.publicId(),
                    "PLATFORM_LOGIN_FAILED",
                    "PLATFORM_ADMIN",
                    account.publicId(),
                    "{\"reason\":\"INVALID_PASSWORD\"}",
                    auditTraceId(account.publicId())
            );
            throw new PlatformApiException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        repository.resetLoginFailures(account.id(), now);
        PlatformPrincipal principal = loadPrincipal(account);
        PlatformTokenService.IssuedToken issued = tokenService.issue(principal, now);
        auditService.record(
                account.id(),
                account.publicId(),
                "PLATFORM_LOGIN_SUCCEEDED",
                "PLATFORM_ADMIN",
                account.publicId(),
                "{}",
                auditTraceId(account.publicId())
        );
        return new LoginResult(issued.value(), issued.expiresAt(), principal);
    }

    public PlatformPrincipal resolveToken(String token) {
        PlatformPrincipal claims = tokenService.parse(token);
        PlatformAdminAccount account = repository.findById(claims.userId())
                .orElseThrow(PlatformAuthService::invalidToken);
        if (!account.active() || account.lockedAt(clock.now())) {
            throw invalidToken();
        }
        return loadPrincipal(account);
    }

    private PlatformPrincipal loadPrincipal(PlatformAdminAccount account) {
        Set<String> roleCodes = repository.findRoleCodes(account.id());
        Set<String> permissionCodes = repository.findPermissionCodes(account.id());
        if (roleCodes.isEmpty()) {
            throw invalidToken();
        }
        return new PlatformPrincipal(
                account.id(),
                account.publicId(),
                account.username(),
                roleCodes,
                permissionCodes
        );
    }

    private static PlatformApiException invalidToken() {
        return new PlatformApiException(ErrorCode.TOKEN_INVALID, "登录凭证无效或已过期");
    }

    private static String auditTraceId(String actorPublicId) {
        return "auth_" + actorPublicId;
    }

    public record LoginResult(
            String accessToken,
            Instant expiresAt,
            PlatformPrincipal principal
    ) {
    }
}
