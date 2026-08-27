package com.rc.longxinnan.provider;

import com.rc.longxinnan.alert.AlertEvent;
import com.rc.longxinnan.alert.AlertNotifier;
import com.rc.longxinnan.config.ProviderProperties;
import com.rc.longxinnan.config.ProviderProperties.AlertConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按供应商维护进程内的连续失败计数，并在跨过阈值且超过冷却期时触发告警。
 *
 * <p>同步与轮询两条投递路径都经由 {@code NotificationDeliveryService} 调用本组件，
 * 保证告警口径一致。计数保存在内存中，重启会重置（MVP 可接受）。
 */
@Component
public class ProviderFailureTracker {

    private static final Logger log = LoggerFactory.getLogger(ProviderFailureTracker.class);

    private final Map<String, ProviderProperties.ProviderConfig> configs;
    private final AlertNotifier alertNotifier;
    private final Clock clock;

    private final Map<String, Integer> streaks = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    public ProviderFailureTracker(ProviderProperties properties, AlertNotifier alertNotifier, Clock clock) {
        this.configs = properties.providers() != null ? properties.providers() : Map.of();
        this.alertNotifier = alertNotifier;
        this.clock = clock;
    }

    /** 一次成功投递后重置该供应商的连续失败计数。 */
    public void recordSuccess(String provider) {
        streaks.remove(provider);
    }

    /** 一次失败投递后累加连续失败计数，并按阈值 + 冷却期判定是否触发告警。 */
    public void recordFailure(String provider, Long notificationId, String error) {
        int streak = streaks.merge(provider, 1, Integer::sum);
        AlertConfig alert = configs.get(provider) != null ? configs.get(provider).alert() : null;
        if (alert == null || !alert.enabled()) {
            return;
        }
        int threshold = alert.consecutiveFailures();
        if (streak < threshold) {
            return;
        }
        Instant now = clock.instant();
        Instant last = lastAlertAt.get(provider);
        long cooldownMs = Duration.ofMinutes(alert.cooldownMinutes()).toMillis();
        if (last != null && Duration.between(last, now).toMillis() < cooldownMs) {
            return; // 冷却期内静默，避免告警风暴
        }
        lastAlertAt.put(provider, now);
        log.warn("provider={} streak={}/{} crossing threshold, firing alert", provider, streak, threshold);
        alertNotifier.alert(new AlertEvent(provider, streak, threshold, notificationId, error, alert.recipients()));
    }
}
