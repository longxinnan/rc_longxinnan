package com.rc.longxinnan.alert;

import java.util.List;

/**
 * 一次告警事件的描述。
 *
 * @param provider      供应商名
 * @param streak        当前连续失败次数
 * @param threshold     触发阈值
 * @param notificationId 最近一条失败消息的 ID（供排障定位）
 * @param error         最近一次失败原因
 * @param recipients    收件人列表（来自供应商 yaml 的 alert.recipients）
 */
public record AlertEvent(
        String provider,
        int streak,
        int threshold,
        Long notificationId,
        String error,
        List<String> recipients) {
}
