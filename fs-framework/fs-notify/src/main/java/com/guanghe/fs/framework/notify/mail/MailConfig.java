package com.guanghe.fs.framework.notify.mail;


import com.guanghe.fs.framework.notify.mail.service.NoOpJavaMailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 *
 * @author Yann
 * @date 2025/11/20 11:30
 */
@Slf4j
@Configuration
public class MailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.mail", name = "enable", havingValue = "false")
    public JavaMailSender dummyMailSender() {
        log.warn("检测到 spring.mail.enable=false，已启用[空邮件发送器]，邮件发送功能将被拦截。");
        return new NoOpJavaMailSender();
    }
}
