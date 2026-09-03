package com.guanghe.fs.framework.notify.mail;

import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.framework.notify.mail.domain.Mail;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@Service
public class MailService {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private TemplateEngine templateEngine;

    @Value("${spring.mail.form}")
    private String form;

    /**
     * 发送普通文本
     *
     * @param mail 邮件信息
     */
    public void sendTextMail(Mail mail){
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(form);
            helper.setTo(mail.getRecipient());
            helper.setSubject(mail.getSubject());
            helper.setText(mail.getContent(), true);
            mailSender.send(message);
            log.info("Email sent successfully to {} with subject '{}', content: {}", mail.getRecipient(), mail.getSubject(), mail.getContent());
        } catch (Exception e) {
            log.error("Failed to send email to {} with subject '{}', content: {}", mail.getRecipient(), mail.getSubject(), mail.getContent());
            e.printStackTrace();
        }
    }

    /**
     * 发送HTML
     *
     * @param mail 邮件信息
     *
     */
    public void sendHtmlMail(Mail mail) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(form);
            helper.setTo(mail.getRecipient());
            helper.setSubject(mail.getSubject());
            Context context = new Context();
            context.setVariables(mail.getParams());
            String emailContent = templateEngine.process(mail.getHtmlTemplate(), context);
            helper.setText(emailContent, true);
            mailSender.send(message);
            log.info("Email sent successfully to {} with subject '{}', htmlTemplate: {}, content: {}",
                    mail.getRecipient(), mail.getSubject(), mail.getHtmlTemplate(), JsonUtils.toJsonString(mail.getParams()));
        } catch (Exception e) {
            log.error("Failed to send email to {} with subject '{}', htmlTemplate: {}, content: {}",
                    mail.getRecipient(), mail.getSubject(), mail.getHtmlTemplate(), JsonUtils.toJsonString(mail.getParams()));
            e.printStackTrace();
        }

    }
}
