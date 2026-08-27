package com.rc.longxinnan.alert;

/**
 * 告警发送渠道 SPI。MVP 仅实现邮件渠道；未来可扩展 IM Webhook（钉钉/企微/Slack）等。
 */
public interface AlertNotifier {

    void alert(AlertEvent event);
}
