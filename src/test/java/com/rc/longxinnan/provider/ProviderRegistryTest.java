package com.rc.longxinnan.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.longxinnan.config.ProviderProperties;
import com.rc.longxinnan.config.ProviderProperties.ProviderConfig;
import com.rc.longxinnan.template.PayloadTemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    private ProviderRegistry registryWith(Map<String, ProviderConfig> providers) {
        return new ProviderRegistry(
                new ProviderProperties(providers),
                new PayloadTemplateRenderer(new ObjectMapper()),
                new ObjectMapper());
    }

    @Test
    void resolvesRegisteredProvider() {
        ProviderConfig cfg = new ProviderConfig("https://x.example.com", HttpMethod.POST, null,
                null, null, null, null, null, "{{payload}}", null);
        ProviderRegistry registry = registryWith(Map.of("crm", cfg));

        assertThat(registry.contains("crm")).isTrue();
        assertThat(registry.config("crm").url()).isEqualTo("https://x.example.com");
        assertThat(registry.notifier("crm")).isInstanceOf(HttpProviderNotifier.class);
    }

    @Test
    void throwsForUnknownProvider() {
        ProviderRegistry registry = registryWith(Map.of());

        assertThat(registry.contains("nope")).isFalse();
        assertThatThrownBy(() -> registry.config("nope")).isInstanceOf(UnknownProviderException.class);
        assertThatThrownBy(() -> registry.notifier("nope")).isInstanceOf(UnknownProviderException.class);
    }
}
