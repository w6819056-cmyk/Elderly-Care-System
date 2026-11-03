package com.smartelder.platform.core.common;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public final class PlatformClock {

    private final Clock clock;

    public PlatformClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Instant now() {
        return clock.instant();
    }

    public OffsetDateTime utcNow() {
        return OffsetDateTime.ofInstant(now(), ZoneOffset.UTC);
    }
}
