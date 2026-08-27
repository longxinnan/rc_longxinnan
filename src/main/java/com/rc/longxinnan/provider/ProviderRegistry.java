package com.rc.longxinnan.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.config.ProviderProperties;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.template.PayloadTemplateRenderer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供应商注册表：按 app.providers 配置构建每个供应商的 HTTP 适配器，供名称查找。
 */
@Component
public class ProviderRegistry {

    private final Map<String, ProviderConfig> configs;
    private final Map<String, ProviderNotifier> notifiers;

    public ProviderRegistry(ProviderProperties properties, PayloadTemplateRenderer renderer, ObjectMapper objectMapper) {
        Map<String, ProviderConfig> cfgMap = new ConcurrentHashMap<>();
        Map<String, ProviderNotifier> notifierMap = new ConcurrentHashMap<>();
        if (properties.providers() != null) {
            properties.providers().forEach((name, cfg) -> {
                cfgMap.put(name, cfg);
                notifierMap.put(name, new HttpProviderNotifier(name, cfg, renderer, objectMapper, buildRestClient(cfg)));
            });
        }
        this.configs = Map.copyOf(cfgMap);
        this.notifiers = Map.copyOf(notifierMap);
    }

    public ProviderConfig config(String provider) {
        ProviderConfig cfg = configs.get(provider);
        if (cfg == null) {
            throw new UnknownProviderException(provider);
        }
        return cfg;
    }

    public ProviderNotifier notifier(String provider) {
        ProviderNotifier notifier = notifiers.get(provider);
        if (notifier == null) {
            throw new UnknownProviderException(provider);
        }
        return notifier;
    }

    public boolean contains(String provider) {
        return configs.containsKey(provider);
    }

    private static RestClient buildRestClient(ProviderConfig cfg) {
        return RestClient.builder()
                .requestFactory(requestFactory(cfg))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(ProviderConfig cfg) {
        // JdkClientHttpRequestFactory 没有 connectTimeout 设置项：连接超时须在底层
        // JDK HttpClient 上配置，读超时在工厂上按请求设置。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(cfg.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(cfg.readTimeout());
        return factory;
    }
}
