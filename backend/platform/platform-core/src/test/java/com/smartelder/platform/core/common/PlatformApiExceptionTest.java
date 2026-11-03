package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartelder.common.ErrorCode;
import org.junit.jupiter.api.Test;

class PlatformApiExceptionTest {

    @Test
    void carriesStableErrorCodeAndResolvedMessage() {
        IllegalStateException cause = new IllegalStateException("root");
        PlatformApiException explicit = new PlatformApiException(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "幂等键已被其他请求使用",
                cause
        );
        PlatformApiException defaultMessage = new PlatformApiException(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                null
        );

        assertThat(explicit.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(explicit.getMessage()).isEqualTo("幂等键已被其他请求使用");
        assertThat(explicit.getCause()).isSameAs(cause);
        assertThat(defaultMessage.code()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(defaultMessage.getMessage())
                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION.defaultMessage());
    }
}
