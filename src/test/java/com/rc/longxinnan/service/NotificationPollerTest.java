package com.rc.longxinnan.service;

import com.rc.longxinnan.config.PollerProperties;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.provider.ProviderRegistry;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPollerTest {

    private final OutboxNotificationRepository repository = mock(OutboxNotificationRepository.class);
    private final ProviderRegistry registry = mock(ProviderRegistry.class);
    private final NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
    private NotificationPoller poller;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        poller = new NotificationPoller(repository, registry, deliveryService, new PollerProperties(2000, 50, 1), tm);
    }

    private static OutboxNotification row(long id, String provider) {
        OutboxNotification n = new OutboxNotification();
        n.setId(id);
        n.setProvider(provider);
        n.setStatus(DispatchStatus.PENDING);
        n.setNextAttemptAt(Instant.now());
        return n;
    }

    @Test
    void claimsAndDeliversEachRow() {
        when(repository.claimPending(any(), anyInt())).thenReturn(List.of(row(1L, "crm"), row(2L, "adsystem")));
        when(registry.contains("crm")).thenReturn(true);
        when(registry.contains("adsystem")).thenReturn(true);

        poller.run();

        verify(deliveryService, times(2)).deliver(any());
    }

    @Test
    void skipsWhenNothingClaimed() {
        when(repository.claimPending(any(), anyInt())).thenReturn(List.of());

        poller.run();

        verify(deliveryService, never()).deliver(any());
    }

    @Test
    void unknownProviderFailsDirectly() {
        OutboxNotification row = row(1L, "ghost");
        when(repository.claimPending(any(), anyInt())).thenReturn(List.of(row));
        when(registry.contains("ghost")).thenReturn(false);
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        poller.run();

        assertThat(row.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(row.getLastError()).isEqualTo("provider not configured");
        verify(deliveryService, never()).deliver(any());
    }
}
