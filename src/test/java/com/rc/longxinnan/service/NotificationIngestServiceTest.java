package com.rc.longxinnan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.api.dto.NotificationRequest;
import com.rc.longxinnan.api.dto.NotificationResponse;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.DispatchMode;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.provider.UnknownProviderException;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationIngestServiceTest {

    private final OutboxNotificationRepository repository = mock(OutboxNotificationRepository.class);
    private final ProviderRegistry registry = mock(ProviderRegistry.class);
    private final NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationIngestService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new NotificationIngestService(repository, registry, deliveryService, objectMapper, tm);
        doAnswer(inv -> {
            OutboxNotification e = inv.getArgument(0);
            e.setId(99L);
            return e;
        }).when(repository).saveAndFlush(any());
    }

    private static ProviderConfig config(DispatchMode mode, int maxAttempts) {
        return new ProviderConfig("https://x", HttpMethod.POST, null, mode,
                null, null, maxAttempts, 1000L, "{{payload}}", null);
    }

    private static NotificationRequest request(String eventId, String provider) {
        return new NotificationRequest(eventId, provider, "EVENT", Map.of("a", 1), null);
    }

    @Test
    void asyncPersistsWithoutDelivering() {
        when(registry.config("adsystem")).thenReturn(config(DispatchMode.ASYNC, 5));
        when(repository.findByProviderAndEventId("adsystem", "evt-1")).thenReturn(Optional.empty());

        NotificationResponse resp = service.submit(request("evt-1", "adsystem"));

        assertThat(resp.notificationId()).isEqualTo(99L);
        assertThat(resp.status()).isEqualTo(DispatchStatus.PENDING.name());
        verify(repository).saveAndFlush(any());
        verify(deliveryService, never()).deliver(any());
    }

    @Test
    void syncSuccessDeliversAfterPersist() {
        when(registry.config("crm")).thenReturn(config(DispatchMode.SYNC, 5));
        when(repository.findByProviderAndEventId("crm", "evt-1")).thenReturn(Optional.empty());
        when(deliveryService.deliver(any())).thenReturn(DispatchStatus.SUCCESS);

        NotificationResponse resp = service.submit(request("evt-1", "crm"));

        assertThat(resp.status()).isEqualTo(DispatchStatus.SUCCESS.name());
        InOrder inOrder = inOrder(repository, deliveryService);
        inOrder.verify(repository).saveAndFlush(any());   // 先落库
        inOrder.verify(deliveryService).deliver(any());   // 再投递
    }

    @Test
    void syncFailureFallsBackToPending() {
        when(registry.config("crm")).thenReturn(config(DispatchMode.SYNC, 5));
        when(repository.findByProviderAndEventId("crm", "evt-1")).thenReturn(Optional.empty());
        when(deliveryService.deliver(any())).thenReturn(DispatchStatus.PENDING); // 同步失败 -> 轮询兜底

        NotificationResponse resp = service.submit(request("evt-1", "crm"));

        assertThat(resp.status()).isEqualTo(DispatchStatus.PENDING.name());
    }

    @Test
    void dedupReturnsExistingWithoutSave() {
        OutboxNotification existing = new OutboxNotification();
        existing.setId(88L);
        existing.setStatus(DispatchStatus.SUCCESS);
        when(registry.config("crm")).thenReturn(config(DispatchMode.SYNC, 5));
        when(repository.findByProviderAndEventId("crm", "evt-1")).thenReturn(Optional.of(existing));

        NotificationResponse resp = service.submit(request("evt-1", "crm"));

        assertThat(resp.notificationId()).isEqualTo(88L);
        assertThat(resp.status()).isEqualTo(DispatchStatus.SUCCESS.name());
        verify(repository, never()).saveAndFlush(any());
        verify(deliveryService, never()).deliver(any());
    }

    @Test
    void dedupRaceOnInsertReturnsExisting() {
        OutboxNotification dup = new OutboxNotification();
        dup.setId(88L);
        dup.setStatus(DispatchStatus.PENDING);
        when(registry.config("crm")).thenReturn(config(DispatchMode.SYNC, 5));
        when(repository.findByProviderAndEventId("crm", "evt-1"))
                .thenReturn(Optional.empty())          // 先查无
                .thenReturn(Optional.of(dup));         // 冲突后重查有
        doThrow(new DataIntegrityViolationException("dup")).when(repository).saveAndFlush(any());

        NotificationResponse resp = service.submit(request("evt-1", "crm"));

        assertThat(resp.notificationId()).isEqualTo(88L);
        verify(deliveryService, never()).deliver(any());
    }

    @Test
    void requestMaxAttemptsOverridesProviderDefault() {
        when(registry.config("adsystem")).thenReturn(config(DispatchMode.ASYNC, 5));
        when(repository.findByProviderAndEventId("adsystem", "evt-1")).thenReturn(Optional.empty());

        service.submit(new NotificationRequest("evt-1", "adsystem", "EVENT", Map.of("a", 1), 7));

        ArgumentCaptor<OutboxNotification> captor = ArgumentCaptor.forClass(OutboxNotification.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMaxAttempts()).isEqualTo(7);
    }

    @Test
    void unknownProviderThrows() {
        when(registry.config("nope")).thenThrow(new UnknownProviderException("nope"));
        assertThatThrownBy(() -> service.submit(request("evt-1", "nope")))
                .isInstanceOf(UnknownProviderException.class);
    }
}
