package com.rc.longxinnan.service;

import com.rc.longxinnan.config.PollerProperties;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 异步投递轮询器：定期用 SKIP LOCKED 认领一批到期 PENDING 记录并逐条投递。
 *
 * <p>fixedDelay 防止两轮重叠；认领是短事务（提交即释放行锁），投递不持有锁。
 * 退避通过 next_attempt_at 未来时间点实现"免等待"，调度线程不会阻塞。
 */
@Component
public class NotificationPoller {

    private static final Logger log = LoggerFactory.getLogger(NotificationPoller.class);

    private final OutboxNotificationRepository repository;
    private final ProviderRegistry registry;
    private final NotificationDeliveryService deliveryService;
    private final PollerProperties pollerProperties;
    private final TransactionTemplate txTemplate;

    public NotificationPoller(OutboxNotificationRepository repository, ProviderRegistry registry,
                              NotificationDeliveryService deliveryService, PollerProperties pollerProperties,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.deliveryService = deliveryService;
        this.pollerProperties = pollerProperties;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.poller.fixed-delay-ms:2000}")
    public void run() {
        List<OutboxNotification> batch = repository.claimPending(Instant.now(), pollerProperties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.info("poller tick: claimed={}", batch.size());
        for (OutboxNotification n : batch) {
            if (registry.contains(n.getProvider())) {
                deliveryService.deliver(n);
            } else {
                failProviderNotConfigured(n.getId());
            }
        }
    }

    /** 供应商配置漂移：直接判死信而非无限重试。 */
    private void failProviderNotConfigured(Long id) {
        txTemplate.executeWithoutResult(status -> {
            OutboxNotification n = repository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("notification not found: " + id));
            n.setStatus(DispatchStatus.FAILED);
            n.setLastError("provider not configured");
            n.setLastAttemptAt(Instant.now());
            repository.save(n);
            log.error("provider=UNKNOWN notificationId={} outcome=DEAD_LETTER lastError=provider not configured", id);
        });
    }
}
