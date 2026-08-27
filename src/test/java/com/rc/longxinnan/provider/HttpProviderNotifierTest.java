package com.rc.longxinnan.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.template.PayloadTemplateRenderer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderNotifierTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private HttpProviderNotifier notifier(ProviderConfig cfg) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build());
        factory.setReadTimeout(cfg.readTimeout());
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return new HttpProviderNotifier("crm", cfg, new PayloadTemplateRenderer(mapper), mapper, client);
    }

    @Test
    void sendsConfiguredHeadersAndTemplateBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        ProviderConfig cfg = new ProviderConfig(
                server.url("/crm").toString(),
                HttpMethod.POST,
                Map.of("Authorization", "Bearer t"),
                null, null, null, null, null,
                "{ \"customerId\": {{payload.customerId}}, \"status\": {{payload.status}} }",
                null);

        OutboxNotification n = new OutboxNotification();
        n.setId(42L);
        n.setProvider("crm");
        n.setPayload(mapper.writeValueAsString(Map.of("customerId", "C-42", "status", "ACTIVE")));

        DeliveryResult result = notifier(cfg).send(n);

        assertThat(result.success()).isTrue();
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/crm");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer t");
        assertThat(req.getHeader("X-Request-Id")).isEqualTo("42");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"C-42\"").contains("ACTIVE");
    }

    @Test
    void returnsFailureOnServerError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        ProviderConfig cfg = new ProviderConfig(server.url("/crm").toString(), HttpMethod.POST, null,
                null, null, null, null, null, "{{payload}}", null);
        OutboxNotification n = new OutboxNotification();
        n.setId(1L);
        n.setPayload("{\"a\":1}");

        DeliveryResult result = notifier(cfg).send(n);

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 10, unit = TimeUnit.SECONDS)
    void returnsFailureOnReadTimeout() throws Exception {
        // 服务器不返回任何响应 -> JdkClientHttpRequestFactory 的响应超时（200ms）触发 -> failure
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ProviderConfig cfg = new ProviderConfig(server.url("/crm").toString(), HttpMethod.POST, null,
                null, Duration.ofSeconds(2), Duration.ofMillis(200), null, null, "{{payload}}", null);
        OutboxNotification n = new OutboxNotification();
        n.setId(1L);
        n.setPayload("{\"a\":1}");

        DeliveryResult result = notifier(cfg).send(n);

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(-1);
    }

    @Test
    void rejectsMissingUrl() {
        ProviderConfig cfg = new ProviderConfig(null, HttpMethod.POST, null,
                null, null, null, null, null, "{{payload}}", null);
        try {
            notifier(cfg);
            assertThat(true).as("should have thrown").isFalse();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("no url");
        }
    }
}
