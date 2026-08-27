package com.rc.longxinnan.api.dto;

/**
 * 入站通知响应。契约统一返回 202 Accepted，业务系统无需关心供应商投递结果。
 *
 * @param notificationId outbox 记录 ID（也是出站 HTTP 请求的 X-Request-Id，供供应商幂等）
 * @param status         当前投递状态（信息性：SUCCESS/PENDING/FAILED）
 */
public record NotificationResponse(long notificationId, String status) {
}
