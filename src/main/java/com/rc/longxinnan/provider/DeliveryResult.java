package com.rc.longxinnan.provider;

/**
 * 一次供应商投递的结果。
 *
 * @param success    是否成功（收到 2xx）
 * @param httpStatus HTTP 状态码；-1 表示连接/超时/其他非 HTTP 异常
 * @param error      失败原因（供死信留痕与告警展示）
 */
public record DeliveryResult(boolean success, int httpStatus, String error) {

    public static DeliveryResult success(int httpStatus) {
        return new DeliveryResult(true, httpStatus, null);
    }

    public static DeliveryResult failure(int httpStatus, String error) {
        return new DeliveryResult(false, httpStatus, error);
    }
}
