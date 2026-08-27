package com.rc.longxinnan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.api.dto.NotificationRequest;
import com.rc.longxinnan.api.dto.NotificationResponse;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.DispatchMode;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 通知入站编排：校验 → 幂等去重 → 先落库 → 按供应商投递模式分发 → 统一响应。
 *
 * <p>关键不变量：<b>先落库再投递</b>。落库在独立短事务中完成（返回即提交、行已持久化）；
 * 同步投递在请求线程内进行但处于事务之外，失败/超时保持 PENDING 交由轮询兜底，
 * 因此即使同步尝试期间进程崩溃，消息也不会丢失（至少一次）。
 */
@Service
public class NotificationIngestService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIngestService.class);

    private final OutboxNotificationRepository repository;
    private final ProviderRegistry registry;
    private final NotificationDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public NotificationIngestService(OutboxNotificationRepository repository, ProviderRegistry registry,
                                     NotificationDeliveryService deliveryService, ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交一条通知。对业务系统的契约统一为 202（校验失败由控制器返回 400），
     * 业务系统永远看不到供应商投递结果。
     */
    public NotificationResponse submit(NotificationRequest request) {
        ProviderConfig config = registry.config(request.provider());

        PersistResult result = persist(request, config);
        if (result.duplicate()) {
            return new NotificationResponse(result.id(), result.status().name());
        }

        if (config.dispatchMode() == DispatchMode.SYNC) {
            DispatchStatus finalStatus = deliveryService.deliver(result.entity());
            return new NotificationResponse(result.id(), finalStatus.name());
        }
        // ASYNC：保持 PENDING，轮询器稍后批量投递
        return new NotificationResponse(result.id(), DispatchStatus.PENDING.name());
    }

    /**
     * 幂等去重 + 落库。整个操作在一个短事务内：先查、再插，唯一约束
     * (provider, event_id) 兜底并发重复提交。
     */
    private PersistResult persist(NotificationRequest request, ProviderConfig config) {
        return txTemplate.execute(status -> {
            Optional<OutboxNotification> existing =
                    repository.findByProviderAndEventId(request.provider(), request.eventId());
            if (existing.isPresent()) {
                OutboxNotification e = existing.get();
                log.info("dedup hit provider={} eventId={} notificationId={} status={}",
                        e.getProvider(), e.getEventId(), e.getId(), e.getStatus());
                return PersistResult.duplicate(e.getId(), e.getStatus());
            }

            OutboxNotification entity = new OutboxNotification();
            entity.setProvider(request.provider());
            entity.setEventId(request.eventId());
            entity.setEventType(request.eventType());
            entity.setPayload(writePayload(request.payload()));
            entity.setStatus(DispatchStatus.PENDING);
            entity.setAttemptCount(0);
            entity.setMaxAttempts(resolveMaxAttempts(request, config));
            entity.setNextAttemptAt(Instant.now());

            try {
                repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException e) {
                // 并发重复插入：唯一约束拦截后重查返回既有记录
                OutboxNotification dup = repository.findByProviderAndEventId(request.provider(), request.eventId())
                        .orElseThrow(() -> new IllegalStateException("duplicate insert race with no row found", e));
                return PersistResult.duplicate(dup.getId(), dup.getStatus());
            }
            return PersistResult.created(entity);
        });
    }

    /** maxAttempts：请求参数优先，否则取供应商配置默认值（入站时快照）。 */
    private int resolveMaxAttempts(NotificationRequest request, ProviderConfig config) {
        return request.maxAttempts() != null ? request.maxAttempts() : config.maxAttempts();
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid payload", e);
        }
    }

    private record PersistResult(Long id, DispatchStatus status, OutboxNotification entity, boolean duplicate) {

        static PersistResult created(OutboxNotification entity) {
            return new PersistResult(entity.getId(), entity.getStatus(), entity, false);
        }

        static PersistResult duplicate(Long id, DispatchStatus status) {
            return new PersistResult(id, status, null, true);
        }
    }
}
