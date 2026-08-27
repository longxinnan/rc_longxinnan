package com.rc.longxinnan.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailAlertNotifierTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void sendsAlertEmail() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailAlertNotifier notifier = new EmailAlertNotifier(provider(mailSender));

        notifier.alert(new AlertEvent("crm", 5, 3, 42L, "HTTP 503", List.of("ops@x.com")));

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getTo()).containsExactly("ops@x.com");
        assertThat(msg.getSubject()).contains("crm").contains("5/3");
        assertThat(msg.getText()).contains("42");
    }

    @Test
    void skipsWhenMailSenderAbsent() {
        EmailAlertNotifier notifier = new EmailAlertNotifier(provider(null));
        notifier.alert(new AlertEvent("crm", 5, 3, 42L, "e", List.of("ops@x.com")));
        // 不抛异常、不发送
    }

    @Test
    void skipsWhenNoRecipients() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailAlertNotifier notifier = new EmailAlertNotifier(provider(mailSender));

        notifier.alert(new AlertEvent("crm", 5, 3, 42L, "e", List.of()));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void swallowsMailSendException() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("boom")).when(mailSender).send(any(SimpleMailMessage.class));
        EmailAlertNotifier notifier = new EmailAlertNotifier(provider(mailSender));

        notifier.alert(new AlertEvent("crm", 5, 3, 42L, "e", List.of("ops@x.com")));
        // 发送失败被吞掉，不影响主链路
    }
}
