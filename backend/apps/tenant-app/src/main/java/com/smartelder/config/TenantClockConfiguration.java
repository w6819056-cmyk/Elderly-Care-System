package com.smartelder.config;

import java.time.Clock;
import com.smartelder.api.tenant.licensing.TenantLicenseVerifier;
import com.smartelder.platform.licensing.Ed25519LicenseVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.PublicKey;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TenantLicenseProperties.class)
public class TenantClockConfiguration {

    @Bean
    public Clock tenantClock() {
        return Clock.systemUTC();
    }

    @Bean
    public Map<String, PublicKey> trustedLicenseKeys(TenantLicenseProperties properties) {
        return properties.getTrustedKeys().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> Ed25519LicenseVerifier.publicKeyFromPem(entry.getValue())));
    }

    @Bean
    public TenantLicenseVerifier tenantLicenseVerifier(
            Map<String, PublicKey> trustedLicenseKeys, ObjectMapper objectMapper, Clock clock) {
        return new TenantLicenseVerifier(trustedLicenseKeys, objectMapper, clock);
    }
}
