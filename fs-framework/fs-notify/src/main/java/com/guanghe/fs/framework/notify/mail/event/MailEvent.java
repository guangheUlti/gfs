package com.guanghe.fs.framework.notify.mail.event;

import com.guanghe.fs.framework.notify.mail.domain.Mail;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 邮件事件
 *
 * @Author: guangheUlti
 * @Date: 2025/10/22 15:48
 */
@Getter
public class MailEvent extends ApplicationEvent {

    private final Mail mail;

    public MailEvent(Object source, Mail mail) {
        super(source);
        this.mail = mail;
    }
}
