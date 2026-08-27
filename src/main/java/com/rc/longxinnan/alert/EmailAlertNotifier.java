package com.rc.longxinnan.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 邮件告警渠道。
 *
 * <p>使用 {@link ObjectProvider} 处理「未配置 SMTP」的情形：spring-boot-starter-mail
 * 仅在设置了 spring.mail.host 时自动装配 JavaMailSender，缺省时本实现静默跳过，
 * 不影响投递主链路。
 */
@Component
public class EmailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertNotifier.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public EmailAlertNotifier(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @Override
    public void alert(AlertEvent event) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("provider={} alert triggered but SMTP not configured, skip email", event.provider());
            return;
        }
        if (event.recipients() == null || event.recipients().isEmpty()) {
            log.warn("provider={} alert triggered but no recipients configured, skip email", event.provider());
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.recipients().toArray(String[]::new));
        message.setSubject("[rc-longxinnan] 供应商告警: %s 连续失败 %d/%d 次"
                .formatted(event.provider(), event.streak(), event.threshold()));
        message.setText("""
                供应商投递连续失败告警

                供应商: %s
                连续失败: %d / %d 次
                最近失败消息ID: %s
                最近错误: %s
                时间: %s
                """.formatted(event.provider(), event.streak(), event.threshold(),
                event.notificationId(), event.error(), Instant.now()));

        try {
            mailSender.send(message);
            log.info("ALERT sent provider={} streak={}/{} recipients={}",
                    event.provider(), event.streak(), event.threshold(), event.recipients());
        } catch (MailException e) {
            log.error("failed to send alert email provider={}", event.provider(), e);
        }
    }
}
