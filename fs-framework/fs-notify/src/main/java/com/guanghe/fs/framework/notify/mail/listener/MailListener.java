package com.guanghe.fs.framework.notify.mail.listener;

import com.guanghe.fs.framework.notify.mail.MailService;
import com.guanghe.fs.framework.notify.mail.domain.Mail;
import com.guanghe.fs.framework.notify.mail.event.MailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 邮件监听器
 *
 * @Author: guangheUlti
 * @Date: 2025/10/22 15:48
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailListener {

    private final MailService mailService;

    @Async
    @EventListener
    public void handleIrrigControlCommandRecordAddEvent(MailEvent event) {
        Mail mail = event.getMail();
        mailService.sendHtmlMail(mail);
    }
}
