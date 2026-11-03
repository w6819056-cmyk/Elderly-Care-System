package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    @Test
    void sameKeyAndBodyReturnsFirstResultWithoutRunningActionAgain() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_idempotency_replay")) {
            IdempotencyService service = service(database);
            AtomicInteger executions = new AtomicInteger();

            OrderResult first = service.execute(
                    "order.create", "idem-replay", "POST", "/orders", "{\"module\":\"institution\"}",
                    () -> {
                        executions.incrementAndGet();
                        return new OrderResult("ord_01J0000000000000000000000", "CREATED");
                    },
                    OrderResult.class
            );
            OrderResult replay = service.execute(
                    "order.create", "idem-replay", "POST", "/orders", "{\"module\":\"institution\"}",
                    () -> {
                        executions.incrementAndGet();
                        return new OrderResult("ord_different", "CREATED");
                    },
                    OrderResult.class
            );

            assertThat(replay).isEqualTo(first);
            assertThat(executions).hasValue(1);
            assertThat(database.jdbcClient().sql("SELECT COUNT(*) FROM pf_idempotency_record")
                    .query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_idempotency_conflict")) {
            IdempotencyService service = service(database);

            service.execute(
                    "order.create", "idem-conflict", "POST", "/orders", "{\"module\":\"A\"}",
                    () -> new OrderResult("ord_A", "CREATED"),
                    OrderResult.class
            );

            assertThatThrownBy(() -> service.execute(
                    "order.create", "idem-conflict", "POST", "/orders", "{\"module\":\"B\"}",
                    () -> new OrderResult("ord_B", "CREATED"),
                    OrderResult.class
            )).isInstanceOfSatisfying(PlatformApiException.class, exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
                assertThat(exception.getMessage()).contains("幂等键");
            });
        }
    }

    @Test
    void failedActionDoesNotPoisonIdempotencyKey() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_idempotency_retry")) {
            IdempotencyService service = service(database);

            assertThatThrownBy(() -> service.execute(
                    "order.create", "idem-retry", "POST", "/orders", "{\"module\":\"A\"}",
                    () -> {
                        throw new IllegalStateException("temporary failure");
                    },
                    OrderResult.class
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessage("temporary failure");

            OrderResult retried = service.execute(
                    "order.create", "idem-retry", "POST", "/orders", "{\"module\":\"A\"}",
                    () -> new OrderResult("ord_retry", "CREATED"),
                    OrderResult.class
            );

            assertThat(retried.orderPublicId()).isEqualTo("ord_retry");
            assertThat(database.jdbcClient().sql("SELECT COUNT(*) FROM pf_idempotency_record")
                    .query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void concurrentRequestsExecuteActionOnce() throws Exception {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_idempotency_concurrent")) {
            IdempotencyService service = service(database);
            AtomicInteger executions = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<OrderResult> first = executor.submit(() -> {
                    start.await();
                    return executeSlow(service, executions);
                });
                Future<OrderResult> second = executor.submit(() -> {
                    start.await();
                    return executeSlow(service, executions);
                });
                start.countDown();

                OrderResult firstResult = first.get(10, TimeUnit.SECONDS);
                OrderResult secondResult = second.get(10, TimeUnit.SECONDS);

                assertThat(firstResult).isEqualTo(secondResult);
                assertThat(executions).hasValue(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private OrderResult executeSlow(IdempotencyService service, AtomicInteger executions) {
        return service.execute(
                "order.create", "idem-concurrent", "POST", "/orders", "{\"module\":\"A\"}",
                () -> {
                    executions.incrementAndGet();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return new OrderResult("ord_concurrent", "CREATED");
                },
                OrderResult.class
        );
    }

    private IdempotencyService service(PlatformTestDatabase database) {
        PlatformClock clock = new PlatformClock(
                Clock.fixed(Instant.parse("2026-10-30T03:04:05Z"), ZoneOffset.UTC)
        );
        return new IdempotencyService(
                database.jdbcClient(),
                new ObjectMapper().findAndRegisterModules(),
                database.transactionManager(),
                clock
        );
    }

    record OrderResult(String orderPublicId, String status) {
    }
}
