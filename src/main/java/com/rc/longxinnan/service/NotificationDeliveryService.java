package com.rc.longxinnan.service;

import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.DeliveryResult;
import com.rc.longxinnan.provider.ProviderFailureTracker;
import com.rc.longxinnan.provider.ProviderNotifier;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 共享的投递执行器：同步路径与轮询路径都调用 {@link #deliver(OutboxNotification)}，
 * 保证「发送 → 失败计数/告警 → 状态迁移」的口径一致。
 *
 * <p>状态迁移在独立短事务内完成（按 id 重取最新行），投递过程不持有数据库锁。
 */
@Component
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    /** 退避上限 5 分钟，防止个别慢供应商把重试间隔拖得过长。 */
    private static final long MAX_BACKOFF_MS = 5 * 60 * 1000;

    private final ProviderRegistry registry;
    private final ProviderFailureTracker failureTracker;
    private final OutboxNotificationRepository repository;
    private final TransactionTemplate txTemplate;

    public NotificationDeliveryService(ProviderRegistry registry, ProviderFailureTracker failureTracker,
                                       OutboxNotificationRepository repository,
                                       PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.failureTracker = failureTracker;
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 投递一条通知并更新状态，返回投递后的最终状态。
     */
    public DispatchStatus deliver(OutboxNotification entity) {
        String provider = entity.getProvider();
        ProviderConfig config = registry.config(provider);
        ProviderNotifier notifier = registry.notifier(provider);

        DeliveryResult result;
        try {
            result = notifier.send(entity);
        } catch (Exception e) {
            log.warn("provider={} notificationId={} send threw, treated as failure",
                    provider, entity.getId(), e);
            result = DeliveryResult.failure(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (result.success()) {
            failureTracker.recordSuccess(provider);
        } else {
            failureTracker.recordFailure(provider, entity.getId(), result.error());
        }
        return applyState(entity.getId(), result, config);
    }

    private DispatchStatus applyState(Long id, DeliveryResult result, ProviderConfig config) {
        return txTemplate.execute(status -> {
            OutboxNotification n = repository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("notification not found: " + id));
            Instant now = Instant.now();
            n.setLastAttemptAt(now);

            if (result.success()) {
                n.setStatus(DispatchStatus.SUCCESS);
                n.setLastError(null);
                log.info("provider={} notificationId={} eventId={} attempt={} outcome=SUCCESS",
                        n.getProvider(), n.getId(), n.getEventId(), n.getAttemptCount());
                return DispatchStatus.SUCCESS;
            }

            int attempt = n.getAttemptCount() + 1;
            n.setAttemptCount(attempt);
            n.setLastError(result.error());
            if (attempt >= n.getMaxAttempts()) {
                n.setStatus(DispatchStatus.FAILED);
                log.error("provider={} notificationId={} eventId={} attempt={} outcome=DEAD_LETTER lastError={}",
                        n.getProvider(), n.getId(), n.getEventId(), attempt, result.error());
            } else {
                n.setStatus(DispatchStatus.PENDING);
                n.setNextAttemptAt(now.plusMillis(backoffMs(attempt, config)));
                log.warn("provider={} notificationId={} eventId={} attempt={} outcome=RETRY error={} nextAttemptAt={}",
                        n.getProvider(), n.getId(), n.getEventId(), attempt, result.error(), n.getNextAttemptAt());
            }
            repository.save(n);
            return n.getStatus();
        });
    }

    /** 指数退避：base * 2^(attempt-1)，封顶 5 分钟。 */
    private long backoffMs(int attempt, ProviderConfig config) {
        long base = config != null && config.backoffBaseMs() > 0 ? config.backoffBaseMs() : 1000;
        int exponent = Math.min(attempt - 1, 10);
        long delay = base * (long) Math.pow(2, exponent);
        return Math.min(delay, MAX_BACKOFF_MS);
    }
}
