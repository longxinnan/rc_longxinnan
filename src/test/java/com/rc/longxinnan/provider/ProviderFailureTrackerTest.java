package com.rc.longxinnan.provider;

import com.rc.longxinnan.alert.AlertNotifier;
import com.rc.longxinnan.config.ProviderProperties;
import com.rc.longxinnan.config.ProviderProperties.AlertConfig;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ProviderFailureTrackerTest {

    private final Instant t0 = Instant.parse("2026-08-27T10:00:00Z");
    private final AlertNotifier alertNotifier = mock(AlertNotifier.class);

    private ProviderFailureTracker trackerWith(ProviderConfig cfg, Clock clock) {
        return new ProviderFailureTracker(new ProviderProperties(Map.of("crm", cfg)), alertNotifier, clock);
    }

    private ProviderConfig crmWithAlert(boolean enabled, int threshold, int cooldownMinutes) {
        return new ProviderConfig("https://x.example.com", HttpMethod.POST, null,
                null, null, null, null, null, "{{payload}}",
                new AlertConfig(enabled, threshold, cooldownMinutes, List.of("ops@x.com")));
    }

    @Test
    void alertsWhenStreakReachesThreshold() {
        ProviderFailureTracker tracker = trackerWith(crmWithAlert(true, 3, 30), Clock.fixed(t0, ZoneOffset.UTC));

        tracker.recordFailure("crm", 1L, "e1");
        tracker.recordFailure("crm", 2L, "e2");
        verify(alertNotifier, never()).alert(any());

        tracker.recordFailure("crm", 3L, "e3");
        verify(alertNotifier, times(1)).alert(any());
    }

    @Test
    void cooldownSuppressesRepeatedAlerts() {
        // clock 固定不动 -> 冷却期内重复失败不再告警
        ProviderFailureTracker tracker = trackerWith(crmWithAlert(true, 2, 30), Clock.fixed(t0, ZoneOffset.UTC));

        tracker.recordFailure("crm", 1L, "e1");
        tracker.recordFailure("crm", 2L, "e2");
        tracker.recordFailure("crm", 3L, "e3");
        tracker.recordFailure("crm", 4L, "e4");
        verify(alertNotifier, times(1)).alert(any());
    }

    @Test
    void alertFiresAgainAfterCooldownExpires() {
        MutableClock clock = new MutableClock(t0);
        ProviderFailureTracker tracker = trackerWith(crmWithAlert(true, 2, 30), clock);

        tracker.recordFailure("crm", 1L, "e1");
        tracker.recordFailure("crm", 2L, "e2");
        verify(alertNotifier, times(1)).alert(any());

        clock.plus(Duration.ofMinutes(31));
        tracker.recordFailure("crm", 3L, "e3");
        verify(alertNotifier, times(2)).alert(any());
    }

    @Test
    void successResetsStreak() {
        ProviderFailureTracker tracker = trackerWith(crmWithAlert(true, 2, 30), Clock.fixed(t0, ZoneOffset.UTC));

        tracker.recordFailure("crm", 1L, "e1");
        tracker.recordSuccess("crm");
        tracker.recordFailure("crm", 2L, "e2");
        tracker.recordFailure("crm", 3L, "e3"); // 重新累计到 2 -> 触发一次
        verify(alertNotifier, times(1)).alert(any());
    }

    @Test
    void disabledAlertDoesNothing() {
        ProviderFailureTracker tracker = trackerWith(crmWithAlert(false, 2, 30), Clock.fixed(t0, ZoneOffset.UTC));

        tracker.recordFailure("crm", 1L, "e1");
        tracker.recordFailure("crm", 2L, "e2");
        tracker.recordFailure("crm", 3L, "e3");
        verify(alertNotifier, never()).alert(any());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void plus(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
