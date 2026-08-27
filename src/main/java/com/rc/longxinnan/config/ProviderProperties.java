package com.rc.longxinnan.config;

import com.rc.longxinnan.domain.DispatchMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 供应商注册表配置：app.providers.&lt;name&gt;.&lt;prop&gt;。
 *
 * <p>注意前缀是 {@code app}：组件名 {@code providers}（Map）会自动接在 {@code app.} 之后，
 * 因此绑定路径为 {@code app.providers.crm.url} 等，而非 {@code app.providers.providers.*}。
 *
 * <p>headers 的值支持 Spring 环境变量占位符（如 {@code "Bearer ${CRM_API_TOKEN}"}），
 * 启动时由 Environment 解析；body-template 内的字段占位符必须使用
 * {@code {{payload.field}}} 语法，切勿使用 {@code ${...}}（会被属性解析吞掉）。
 */
@ConfigurationProperties(prefix = "app")
public record ProviderProperties(Map<String, ProviderConfig> providers) {

    public record ProviderConfig(
            String url,
            HttpMethod method,
            Map<String, String> headers,
            DispatchMode dispatchMode,
            Duration connectTimeout,
            Duration readTimeout,
            Integer maxAttempts,
            Long backoffBaseMs,
            String bodyTemplate,
            AlertConfig alert) {

        public ProviderConfig {
            method = method != null ? method : HttpMethod.POST;
            dispatchMode = dispatchMode != null ? dispatchMode : DispatchMode.ASYNC;
            connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(5);
            maxAttempts = (maxAttempts != null && maxAttempts > 0) ? maxAttempts : 5;
            backoffBaseMs = (backoffBaseMs != null && backoffBaseMs > 0) ? backoffBaseMs : 1000;
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            alert = alert != null ? alert : new AlertConfig(false, 5, 30, List.of());
        }
    }

    /** 供应商告警配置。MVP 仅支持「连续失败次数」触发 + 邮件渠道。 */
    public record AlertConfig(Boolean enabled, Integer consecutiveFailures, Integer cooldownMinutes, List<String> recipients) {

        public AlertConfig {
            enabled = enabled != null && enabled;
            consecutiveFailures = (consecutiveFailures != null && consecutiveFailures > 0) ? consecutiveFailures : 5;
            cooldownMinutes = (cooldownMinutes != null && cooldownMinutes > 0) ? cooldownMinutes : 30;
            recipients = recipients != null ? List.copyOf(recipients) : List.of();
        }
    }
}
