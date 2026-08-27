package com.rc.longxinnan.provider;

import com.rc.longxinnan.domain.OutboxNotification;

/**
 * 供应商投递适配器 SPI。
 *
 * <p>MVP 由 {@link HttpProviderNotifier} 统一按配置实现；未来模板无法覆盖的
 * 供应商可自行实现本接口并在 {@link ProviderRegistry} 注册。
 */
public interface ProviderNotifier {

    /**
     * 投递一条通知到供应商。实现方不得向上抛投递异常——所有结果必须以
     * {@link DeliveryResult} 表达（成功、失败原因），由上层状态机决定重试/死信。
     */
    DeliveryResult send(OutboxNotification notification);
}
