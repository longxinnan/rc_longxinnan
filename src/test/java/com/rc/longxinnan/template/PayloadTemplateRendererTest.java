package com.rc.longxinnan.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadTemplateRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadTemplateRenderer renderer = new PayloadTemplateRenderer(mapper);

    @Test
    void rendersScalarPlaceholders() {
        Map<String, Object> payload = Map.of("customerId", "C-42", "count", 5, "flag", true);
        String out = renderer.render(
                "{ \"id\": {{payload.customerId}}, \"count\": {{payload.count}}, \"flag\": {{payload.flag}} }",
                payload);
        assertThat(out).contains("\"C-42\"").contains("5").contains("true");
    }

    @Test
    void rendersNestedObjectsInline() {
        Map<String, Object> payload = Map.of(
                "user", Map.of("name", "alice"),
                "data", Map.of("a", 1));
        String out = renderer.render("{ \"user\": {{payload.user}}, \"data\": {{payload.data}} }", payload);
        assertThat(out).contains("\"name\":\"alice\"");
        assertThat(out).contains("\"a\":1");
    }

    @Test
    void missingKeyRendersNull() {
        String out = renderer.render("{ \"x\": {{payload.nope}} }", Map.of());
        assertThat(out).contains("null");
    }

    @Test
    void rawPayloadTemplateRendersWholePayload() {
        assertThat(renderer.render("{{payload}}", Map.of("a", 1))).isEqualTo("{\"a\":1}");
    }

    @Test
    void blankTemplateDefaultsToWholePayload() {
        assertThat(renderer.render("", Map.of("a", 1))).isEqualTo("{\"a\":1}");
    }

    @Test
    void springPlaceholderSyntaxIsLeftUntouched() {
        String out = renderer.render("{ \"token\": \"${CRM_API_TOKEN}\" }", Map.of());
        assertThat(out).contains("${CRM_API_TOKEN}");
    }

    @Test
    void renderedTemplateParsesAsJson() throws Exception {
        Map<String, Object> payload = Map.of("customerId", "C-42", "status", "ACTIVE");
        String out = renderer.render(
                "{ \"customerId\": {{payload.customerId}}, \"status\": {{payload.status}} }", payload);
        assertThat(mapper.readTree(out)).isNotNull();
    }
}
