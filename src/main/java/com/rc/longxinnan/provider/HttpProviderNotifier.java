package com.rc.longxinnan.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.domain.OutboxNotification;
import com.rc.longxinnan.template.PayloadTemplateRenderer;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * 通用 HTTP 供应商适配器：完全由 {@link ProviderConfig} 驱动。
 *
 * <p>按供应商构建并缓存带各自超时的 RestClient；发送时附加配置 headers、
 * {@code X-Request-Id}（= notificationId，供供应商幂等）与模板渲染后的 body。
 * 非 2xx / 超时 / 连接异常一律捕获并转为 {@link DeliveryResult}，不向上抛投递错误。
 */
public class HttpProviderNotifier implements ProviderNotifier {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final String providerName;
    private final ProviderConfig config;
    private final PayloadTemplateRenderer renderer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpProviderNotifier(String providerName, ProviderConfig config, PayloadTemplateRenderer renderer,
                                ObjectMapper objectMapper, RestClient restClient) {
        this.providerName = providerName;
        this.config = config;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        if (config.url() == null || config.url().isBlank()) {
            throw new IllegalStateException("provider '" + providerName + "' has no url configured");
        }
    }

    @Override
    public DeliveryResult send(OutboxNotification notification) {
        try {
            Map<String, Object> payload = objectMapper.readValue(notification.getPayload(), PAYLOAD_TYPE);
            String body = renderer.render(config.bodyTemplate(), payload);

            var response = restClient.method(config.method())
                    .uri(config.url())
                    .headers(headers -> {
                        if (config.headers() != null) {
                            config.headers().forEach(headers::set);
                        }
                        headers.set("X-Request-Id", String.valueOf(notification.getId()));
                        if (headers.getFirst("Content-Type") == null) {
                            headers.setContentType(MediaType.APPLICATION_JSON);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return DeliveryResult.success(response.getStatusCode().value());
        } catch (RestClientResponseException e) {
            return DeliveryResult.failure(e.getStatusCode().value(), e.getStatusCode().toString());
        } catch (ResourceAccessException e) {
            return DeliveryResult.failure(-1, e.getMessage());
        } catch (Exception e) {
            return DeliveryResult.failure(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "HttpProviderNotifier[" + providerName + "]";
    }
}
