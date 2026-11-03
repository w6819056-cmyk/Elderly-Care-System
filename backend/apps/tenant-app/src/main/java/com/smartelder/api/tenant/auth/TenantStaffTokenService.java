package com.smartelder.api.tenant.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TenantStaffTokenService {

    public static final String AUDIENCE = "TENANT_STAFF";
    private static final String ISSUER = "smart-elder-tenant";

    private final SecretKey key;
    private final long ttlMinutes;

    public TenantStaffTokenService(
            @Value("${app.tenant-staff-jwt.secret}") String secret,
            @Value("${app.tenant-staff-jwt.ttl-minutes:720}") long ttlMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("tenant staff JWT secret must contain at least 32 bytes");
        }
        if (ttlMinutes <= 0) throw new IllegalArgumentException("tenant staff JWT TTL must be positive");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(String staffPublicId, Instant issuedAt) {
        if (staffPublicId == null || staffPublicId.isBlank()) {
            throw new IllegalArgumentException("staffPublicId is required");
        }
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(staffPublicId)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public ParsedToken parse(String token, Instant now) {
        if (token == null || token.isBlank()) throw invalidToken();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(now))
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) throw invalidToken();
            return new ParsedToken(subject, claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("invalid or expired tenant staff token");
    }

    public record ParsedToken(String subject, Instant expiresAt) {}
}
