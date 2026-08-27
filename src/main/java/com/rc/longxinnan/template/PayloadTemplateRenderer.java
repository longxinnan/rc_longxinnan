package com.rc.longxinnan.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把供应商的 body 模板 + 业务 payload 渲染为出站请求体。
 *
 * <p>语法：{@code {{payload.a.b}}} 取 payload 中的嵌套字段并输出 JSON 字面量
 * （字符串带引号、对象/数组内联、缺失键为 {@code null}）；{@code {{payload}}}
 * 原样输出整个 payload 的 JSON。模板或为空时等价于 {@code {{payload}}}。
 *
 * <p>重要：模板内禁用 Spring 的 {@code ${...}} 占位符——application.yml 里的
 * {@code ${...}} 会在配置加载阶段被 Environment 解析，模板需用 {@code {{...}}}。
 */
@Component
public class PayloadTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{payload(?:\\.[A-Za-z0-9_]+)*}}");

    private final ObjectMapper objectMapper;

    public PayloadTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(String template, Map<String, Object> payload) {
        if (template == null || template.isBlank()) {
            return writeValue(payload);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolve(payload, matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolve(Map<String, Object> payload, String token) {
        String inner = token.substring(2, token.length() - 2); // 去掉 {{ }}
        Object value = payload;
        if (!inner.equals("payload")) {
            String[] parts = inner.substring("payload.".length()).split("\\.");
            for (String part : parts) {
                if (value instanceof Map<?, ?> map) {
                    value = map.get(part);
                } else {
                    value = null;
                    break;
                }
            }
        }
        return writeValue(value);
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new TemplateRenderingException("failed to serialize payload value", e);
        }
    }
}
