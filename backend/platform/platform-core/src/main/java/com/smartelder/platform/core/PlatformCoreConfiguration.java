package com.smartelder.platform.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.core.common.IdempotencyService;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
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
}
