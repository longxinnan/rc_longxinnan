package com.rc.longxinnan.service;

import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.DeliveryResult;
import com.rc.longxinnan.provider.ProviderFailureTracker;
import com.rc.longxinnan.provider.ProviderNotifier;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTest {

    private final ProviderRegistry registry = mock(ProviderRegistry.class);
    private final ProviderFailureTracker tracker = mock(ProviderFailureTracker.class);
    private final OutboxNotificationRepository repository = mock(OutboxNotificationRepository.class);
    private NotificationDeliveryService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new NotificationDeliveryService(registry, tracker, repository, tm);
    }

    private static final ProviderConfig CRM_CONFIG = new ProviderConfig(
            "https://x", HttpMethod.POST, null, null, null, null, 5, 1000L, "{{payload}}", null);

    private void stubNotifier(DeliveryResult result) {
        when(registry.config("crm")).thenReturn(CRM_CONFIG);
        ProviderNotifier notifier = mock(ProviderNotifier.class);
        when(registry.notifier("crm")).thenReturn(notifier);
        when(notifier.send(any())).thenReturn(result);
    }

    private static OutboxNotification entity() {
        OutboxNotification n = new OutboxNotification();
        n.setId(1L);
        n.setProvider("crm");
        return n;
    }

    private static OutboxNotification row(int attempts, int maxAttempts) {
        OutboxNotification n = new OutboxNotification();
        n.setId(1L);
        n.setProvider("crm");
        n.setStatus(DispatchStatus.PENDING);
        n.setAttemptCount(attempts);
        n.setMaxAttempts(maxAttempts);
        n.setNextAttemptAt(Instant.now());
        return n;
    }

    @Test
    void successMarksRowSuccess() {
        stubNotifier(DeliveryResult.success(200));
        OutboxNotification row = row(0, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        DispatchStatus status = service.deliver(entity());

        assertThat(status).isEqualTo(DispatchStatus.SUCCESS);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.SUCCESS);
        verify(tracker).recordSuccess("crm");
        verify(tracker, never()).recordFailure(any(), any(), any());
    }

    @Test
    void failureIncrementsAttemptAndBacksOff() {
        stubNotifier(DeliveryResult.failure(503, "HTTP 503"));
        OutboxNotification row = row(0, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        DispatchStatus status = service.deliver(entity());

        assertThat(status).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(row.getLastError()).isEqualTo("HTTP 503");
        verify(tracker).recordFailure("crm", 1L, "HTTP 503");
    }

    @Test
    void reachesMaxAttemptsBecomesDeadLetter() {
        stubNotifier(DeliveryResult.failure(503, "HTTP 503"));
        OutboxNotification row = row(4, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        DispatchStatus status = service.deliver(entity());

        assertThat(status).isEqualTo(DispatchStatus.FAILED);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(5);
    }

    @Test
    void sendThrowsTreatedAsFailure() {
        when(registry.config("crm")).thenReturn(CRM_CONFIG);
        ProviderNotifier notifier = mock(ProviderNotifier.class);
        when(registry.notifier("crm")).thenReturn(notifier);
        when(notifier.send(any())).thenThrow(new RuntimeException("boom"));
        OutboxNotification row = row(0, 5);
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        DispatchStatus status = service.deliver(entity());

        assertThat(status).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("boom");
        verify(tracker).recordFailure(any(), any(), any());
    }
}
