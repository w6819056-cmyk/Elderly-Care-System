package com.smartelder.platform.core.auth;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PublicIds;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

public class PlatformTokenService {

    public static final String AUDIENCE = "PLATFORM";
    public static final String ISSUER = "smart-elder-platform";

    private final SecretKey key;
    private final Duration ttl;

    public PlatformTokenService(String secret, Duration ttl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("platform JWT secret must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("platform JWT ttl must be positive");
        }
    }

    public IssuedToken issue(PlatformPrincipal principal, Instant now) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Instant expiresAt = now.plus(ttl);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(principal.publicId())
                .audience().add(AUDIENCE).and()
                .claim("uid", principal.userId())
                .claim("username", principal.username())
                .claim("roles", principal.roleCodes())
                .claim("permissions", principal.permissionCodes())
                .id(PublicIds.newId("jti"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    public PlatformPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = numberClaim(claims, "uid").longValue();
            String publicId = claims.getSubject();
            String username = claims.get("username", String.class);
            return new PlatformPrincipal(
                    userId,
                    publicId,
                    username,
                    stringSet(claims.get("roles", Collection.class)),
                    stringSet(claims.get("permissions", Collection.class))
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    public Duration ttl() {
        return ttl;
    }

    private static Number numberClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("JWT claim " + name + " must be numeric");
    }

    private static Set<String> stringSet(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("JWT authority claims must contain strings");
            }
            result.add(text);
        }
        return Set.copyOf(result);
    }

    private static PlatformApiException invalidToken() {
        return new PlatformApiException(ErrorCode.TOKEN_INVALID, "登录凭证无效或已过期");
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
