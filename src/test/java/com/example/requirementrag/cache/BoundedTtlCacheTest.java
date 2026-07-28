package com.example.requirementrag.cache;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTtlCacheTest {
    @Test
    void expiresAndEvictsOldestEntry() {
        MutableClock clock = new MutableClock();
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(Duration.ofSeconds(5), 2, clock);

        cache.put("one", "1");
        clock.advance(Duration.ofSeconds(1));
        cache.put("two", "2");
        cache.put("three", "3");

        assertThat(cache.get("one")).isEmpty();
        assertThat(cache.get("two")).contains("2");
        clock.advance(Duration.ofSeconds(6));
        assertThat(cache.get("two")).isEmpty();
        assertThat(cache.get("three")).isEmpty();
    }

    @Test
    void zeroCapacityDisablesCache() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(Duration.ofMinutes(1), 0);
        cache.put("key", "value");
        assertThat(cache.get("key")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-28T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
