package com.smartelder.platform.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.core.auth.PlatformAdminRepository;
import com.smartelder.platform.core.auth.PlatformAuthService;
import com.smartelder.platform.core.auth.PlatformTokenService;
import com.smartelder.platform.core.common.IdempotencyService;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
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
}
