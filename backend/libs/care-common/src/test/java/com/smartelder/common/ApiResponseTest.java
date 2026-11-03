package com.smartelder.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void successUsesUtcTimestampAndStableCode() {
        ApiResponse<String> response = ApiResponse.success("ok", "tr_test_123", clock);

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("成功");
        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.traceId()).isEqualTo("tr_test_123");
        assertThat(response.timestamp().toString()).isEqualTo("2026-10-30T00:00Z");
    }

    @Test
    void errorUsesDeclaredCodeAndCustomMessage() {
        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.MODULE_NOT_ASSIGNED,
                "该门店未开通机构康养模块",
                "tr_test_456",
                clock
        );

        assertThat(response.code()).isEqualTo("MODULE_NOT_ASSIGNED");
        assertThat(response.message()).isEqualTo("该门店未开通机构康养模块");
        assertThat(response.data()).isNull();
        assertThat(response.traceId()).isEqualTo("tr_test_456");
    }
}