package com.rc.longxinnan.service;

import com.rc.longxinnan.api.dto.NotificationRequest;
import com.rc.longxinnan.api.dto.NotificationResponse;
import com.rc.longxinnan.domain.DispatchStatus;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.repository.OutboxNotificationRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 端到端集成测试：H2(MODE=MySQL) + 真实 HTTP 回环(MockWebServer) + mock 邮件发送。
 * 轮询器定时触发被拨到 1 小时外，测试手动调用 poller.run()。
 */
@SpringBootTest
class NotificationIntegrationTest {

    static final MockWebServer crmServer = new MockWebServer();
    static final MockWebServer adServer = new MockWebServer();

    static {
        try {
            crmServer.start();
            adServer.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.providers.crm.url", () -> crmServer.url("/crm").toString());
        registry.add("app.providers.adsystem.url", () -> adServer.url("/leads").toString());
        registry.add("app.poller.fixed-delay-ms", () -> "3600000");
    }

    @AfterAll
    static void shutdown() throws Exception {
        crmServer.shutdown();
        adServer.shutdown();
    }

    /** 每个用例前清空服务器上遗留的已记录请求，避免跨用例串扰。 */
    @BeforeEach
    void drainServers() throws Exception {
        while (crmServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
        while (adServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @Autowired
    NotificationIngestService ingestService;

    @Autowired
    NotificationPoller poller;

    @Autowired
    OutboxNotificationRepository repository;

    @MockitoBean
    JavaMailSender mailSender;

    @Test
    void syncNotificationDeliversImmediately() throws Exception {
        crmServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        NotificationResponse resp = ingestService.submit(new NotificationRequest(
                "evt-sync-1", "crm", "SUBSCRIBE", Map.of("customerId", "C-42", "status", "ACTIVE"), null));

        assertThat(resp.status()).isEqualTo("SUCCESS");
        RecordedRequest req = crmServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-Request-Id")).isEqualTo(String.valueOf(resp.notificationId()));
        assertThat(req.getBody().readUtf8()).contains("C-42");
    }

    @Test
    void syncFailureFallsBackToPoller() throws Exception {
        crmServer.enqueue(new MockResponse().setResponseCode(500));

        NotificationResponse resp = ingestService.submit(new NotificationRequest(
                "evt-sync-fail", "crm", "SUBSCRIBE", Map.of("customerId", "C-42", "status", "ACTIVE"), null));

        assertThat(resp.status()).isEqualTo("PENDING");
        OutboxNotification row = repository.findByProviderAndEventId("crm", "evt-sync-fail").orElseThrow();
        assertThat(row.getAttemptCount()).isEqualTo(1);

        // 供应商恢复，手动放行重试
        crmServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        repository.save(row);
        poller.run();

        OutboxNotification after = repository.findByProviderAndEventId("crm", "evt-sync-fail").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DispatchStatus.SUCCESS);
    }

    @Test
    void asyncNotificationDeliveredByPoller() throws Exception {
        adServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        NotificationResponse resp = ingestService.submit(new NotificationRequest(
                "evt-async-1", "adsystem", "LEAD", Map.of("event", "click", "data", Map.of("x", 1)), null));

        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(repository.findByProviderAndEventId("adsystem", "evt-async-1").orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PENDING);

        poller.run();

        assertThat(repository.findByProviderAndEventId("adsystem", "evt-async-1").orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.SUCCESS);
        RecordedRequest req = adServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    @Test
    void deadLetterAfterMaxAttemptsAndAlerts() {
        // adsystem: max-attempts=2, 连续失败阈值=2, mock 邮件在
        adServer.enqueue(new MockResponse().setResponseCode(500));
        adServer.enqueue(new MockResponse().setResponseCode(500));

        ingestService.submit(new NotificationRequest(
                "evt-dead", "adsystem", "LEAD", Map.of("event", "click"), null));

        poller.run(); // attempt 1 -> 失败 -> PENDING + 退避
        OutboxNotification row = repository.findByProviderAndEventId("adsystem", "evt-dead").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);

        // next_attempt_at 门控：未到期，再跑一轮应跳过
        poller.run();
        assertThat(repository.findByProviderAndEventId("adsystem", "evt-dead").orElseThrow().getAttemptCount())
                .isEqualTo(1);

        // 手动放行第二次尝试 -> 死信 + 连续失败达阈值 -> 告警
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        repository.save(row);
        poller.run();

        OutboxNotification dead = repository.findByProviderAndEventId("adsystem", "evt-dead").orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(dead.getAttemptCount()).isEqualTo(2);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void idempotentByEventId() throws Exception {
        adServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        NotificationRequest req = new NotificationRequest(
                "evt-dup", "adsystem", "LEAD", Map.of("event", "click"), null);
        NotificationResponse r1 = ingestService.submit(req);
        NotificationResponse r2 = ingestService.submit(req);

        assertThat(r2.notificationId()).isEqualTo(r1.notificationId());

        poller.run();
        assertThat(repository.findByProviderAndEventId("adsystem", "evt-dup").orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.SUCCESS);
        RecordedRequest req1 = adServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req1).isNotNull();
        assertThat(req1.getHeader("X-Request-Id")).isEqualTo(String.valueOf(r1.notificationId()));
    }

    @Test
    void perMessageMaxAttemptsOverridesProviderDefault() {
        // 供应商默认 max-attempts=2，请求指定 1 -> 首次失败即死信
        adServer.enqueue(new MockResponse().setResponseCode(500));

        ingestService.submit(new NotificationRequest(
                "evt-max1", "adsystem", "LEAD", Map.of("event", "click"), 1));

        poller.run();

        OutboxNotification row = repository.findByProviderAndEventId("adsystem", "evt-max1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
    }
}
