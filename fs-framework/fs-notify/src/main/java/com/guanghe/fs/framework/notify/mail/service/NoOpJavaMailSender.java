package com.guanghe.fs.framework.notify.mail.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.io.InputStream;
import java.util.Properties;

/**
 * 空邮件发送器 - 用于禁用邮件功能时拦截邮件发送
 */
@Slf4j
public class NoOpJavaMailSender implements JavaMailSender {

    private static final Session DUMMY_SESSION = Session.getInstance(new Properties());

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(DUMMY_SESSION);
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        try {
            return new MimeMessage(DUMMY_SESSION, contentStream);
        } catch (Exception e) {
            log.warn("创建 MimeMessage 失败（空邮件发送器）", e);
            return new MimeMessage(DUMMY_SESSION);
        }
    }

    @Override
    public void send(MimeMessage mimeMessage) throws MailException {
        log.info("[空邮件发送器] 拦截邮件发送（MimeMessage）");
        logMailInfo(mimeMessage);
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        log.info("[空邮件发送器] 拦截批量邮件发送，数量: {}", mimeMessages.length);
        for (MimeMessage msg : mimeMessages) {
            logMailInfo(msg);
        }
    }

    @Override
    public void send(MimeMessagePreparator mimeMessagePreparator) throws MailException {
        log.info("[空邮件发送器] 拦截邮件发送（MimeMessagePreparator）");
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) throws MailException {
        log.info("[空邮件发送器] 拦截批量邮件发送，数量: {}", mimeMessagePreparators.length);
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) throws MailException {
        log.info("[空邮件发送器] 拦截简单邮件发送: 收件人={}, 主题={}",
                simpleMessage.getTo(), simpleMessage.getSubject());
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailException {
        log.info("[空邮件发送器] 拦截批量简单邮件发送，数量: {}", simpleMessages.length);
    }

    /**
     * 记录邮件信息（方便调试）
     */
    private void logMailInfo(MimeMessage message) {
        try {
            log.info("  → 收件人: {}", message.getAllRecipients() != null ?
                    String.join(", ", java.util.Arrays.stream(message.getAllRecipients())
                            .map(Object::toString).toArray(String[]::new)) : "无");
            log.info("  → 主题: {}", message.getSubject());
        } catch (Exception e) {
            log.debug("解析邮件信息失败", e);
        }
    }
}
