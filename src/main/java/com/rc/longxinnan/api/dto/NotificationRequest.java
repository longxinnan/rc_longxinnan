package com.rc.longxinnan.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 入站通知请求。
 *
 * @param eventId   业务事件 ID（同一 provider 内唯一，用于幂等去重）
 * @param provider  目标供应商名（须在 app.providers 中注册）
 * @param eventType 事件类型（信息性，可选）
 * @param payload   业务事件数据（任意 JSON 对象，body 模板据此渲染）
 * @param maxAttempts 可选的最大投递次数（1-100）；缺省时取供应商配置默认值
 */
public record NotificationRequest(
        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "provider is required")
        String provider,

        String eventType,

        @NotNull(message = "payload is required")
        Map<String, Object> payload,

        @Min(value = 1, message = "maxAttempts must be >= 1")
        @Max(value = 100, message = "maxAttempts must be <= 100")
        Integer maxAttempts
) {
}
