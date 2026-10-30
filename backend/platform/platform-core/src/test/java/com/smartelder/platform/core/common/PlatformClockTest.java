package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlatformClockTest {

    @Test
    void usesInjectedClockAndAlwaysReturnsUtc() {
        Instant instant = Instant.parse("2026-10-30T01:02:03.456Z");
        PlatformClock platformClock = new PlatformClock(
                Clock.fixed(instant, ZoneId.of("Asia/Shanghai"))
        );

        assertThat(platformClock.now()).isEqualTo(instant);
        assertThat(platformClock.utcNow())
                .isEqualTo(OffsetDateTime.parse("2026-10-30T01:02:03.456Z"));
        assertThat(platformClock.utcNow().getOffset()).isEqualTo(ZoneOffset.UTC);
    }
}
