package com.smartelder.platform.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.core.auth.PlatformAdminRepository;
import com.smartelder.platform.core.auth.PlatformAdminService;
import com.smartelder.platform.core.auth.PlatformAuthService;
import com.smartelder.platform.core.auth.PlatformTokenService;
import com.smartelder.platform.core.catalog.ModuleCatalogService;
import com.smartelder.platform.core.catalog.ModuleVersionService;
import com.smartelder.platform.core.common.IdempotencyService;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.enterprise.ActivationService;
import com.smartelder.platform.core.enterprise.EnterpriseService;
import com.smartelder.platform.core.enterprise.InstanceRequestVerifier;
import com.smartelder.platform.core.enterprise.InstanceService;
import com.smartelder.platform.core.enterprise.LicenseSigningService;
import com.smartelder.platform.licensing.Ed25519LicenseSigner;
import com.smartelder.platform.licensing.LicenseSigner;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PlatformCoreConfiguration {

    @Bean
    PlatformClock platformClock(Clock clock) {
        return new PlatformClock(clock);
    }

    @Bean
    PlatformAuditService platformAuditService(JdbcClient jdbcClient, PlatformClock clock) {
        return new PlatformAuditService(jdbcClient, clock);
    }

    @Bean
    IdempotencyService idempotencyService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PlatformClock clock
    ) {
        return new IdempotencyService(jdbcClient, objectMapper, transactionManager, clock);
    }

    @Bean
    PasswordEncoder platformPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    PlatformAdminRepository platformAdminRepository(JdbcClient jdbcClient) {
        return new PlatformAdminRepository(jdbcClient);
    }

    @Bean
    PlatformTokenService platformTokenService(
            @Value("${platform.jwt.secret}") String secret,
            @Value("${platform.jwt.ttl-hours:8}") long ttlHours
    ) {
        return new PlatformTokenService(secret, Duration.ofHours(ttlHours));
    }

    @Bean
    PlatformAdminService platformAdminService(
            PlatformAdminRepository repository,
            PasswordEncoder passwordEncoder,
            PlatformAuditService auditService,
            PlatformClock clock,
            ObjectMapper objectMapper
    ) {
        return new PlatformAdminService(
                repository,
                passwordEncoder,
                auditService,
                clock,
                objectMapper
        );
    }
    @Bean
    PlatformAuthService platformAuthService(
            PlatformAdminRepository repository,
            PasswordEncoder passwordEncoder,
            PlatformTokenService tokenService,
            PlatformAuditService auditService,
            PlatformClock clock
    ) {
        return new PlatformAuthService(
                repository,
                passwordEncoder,
                tokenService,
                auditService,
                clock
        );
    }

    @Bean
    LicenseSigner platformLicenseSigner(
            @Value("${platform.license.key-id:platform-license-v1}") String keyId,
            @Value("${platform.license.signing-private-key}") String privateKeyPem
    ) {
        return Ed25519LicenseSigner.fromPem(
                keyId,
                privateKeyPem.replace("\\n", "\n")
        );
    }

    @Bean
    EnterpriseService platformEnterpriseService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService
    ) {
        return new EnterpriseService(jdbcClient, clock, auditService);
    }

    @Bean
    LicenseSigningService platformLicenseSigningService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            LicenseSigner licenseSigner,
            PlatformClock clock
    ) {
        return new LicenseSigningService(jdbcClient, objectMapper, licenseSigner, clock);
    }

    @Bean
    ActivationService platformActivationService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            EnterpriseService enterpriseService,
            LicenseSigningService licenseSigningService,
            PlatformTransactionManager transactionManager
    ) {
        return new ActivationService(
                jdbcClient,
                clock,
                auditService,
                enterpriseService,
                licenseSigningService,
                transactionManager
        );
    }

    @Bean
    InstanceRequestVerifier platformInstanceRequestVerifier(
            JdbcClient jdbcClient,
            PlatformClock clock
    ) {
        return new InstanceRequestVerifier(jdbcClient, clock);
    }

    @Bean
    InstanceService platformInstanceService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            EnterpriseService enterpriseService,
            LicenseSigningService licenseSigningService
    ) {
        return new InstanceService(
                jdbcClient,
                clock,
                enterpriseService,
                licenseSigningService
        );
    }

    @Bean
    ModuleVersionService platformModuleVersionService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        return new ModuleVersionService(
                jdbcClient,
                clock,
                auditService,
                transactionManager
        );
    }

    @Bean
    ModuleCatalogService platformModuleCatalogService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            ModuleVersionService versionService,
            PlatformTransactionManager transactionManager
    ) {
        return new ModuleCatalogService(
                jdbcClient,
                clock,
                auditService,
                versionService,
                transactionManager
        );
    }
}
