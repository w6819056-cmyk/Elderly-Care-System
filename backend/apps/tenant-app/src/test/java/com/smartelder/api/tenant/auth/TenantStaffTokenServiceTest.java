package com.smartelder.api.tenant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

class TenantStaffTokenServiceTest {

    private static final String SECRET = "tenant-staff-test-secret-that-is-at-least-32-bytes-long";

    @Test
    void issuesAndParsesOnlyTenantStaffAudience() {
        TenantStaffTokenService service = new TenantStaffTokenService(SECRET, 60);
        Instant now = Instant.parse("2026-10-31T12:00:00Z");

        String token = service.issue("a4d81e83-cab7-49d0-8bb8-f7f0e36d805e", now);

        assertThat(service.parse(token, now).subject())
                .isEqualTo("a4d81e83-cab7-49d0-8bb8-f7f0e36d805e");
        assertThatThrownBy(() -> service.parse(token, now.plusSeconds(3601)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLegacyTokenWithoutTenantAudience() {
        TenantStaffTokenService service = new TenantStaffTokenService(SECRET, 60);
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        String legacyToken = Jwts.builder()
                .subject("legacy-user")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> service.parse(legacyToken, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
