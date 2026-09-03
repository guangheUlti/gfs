package com.guanghe.fs.framework.common.utils;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 国际化工具类
 *
 * @Author: guangheUlti
 * @Date: 2026/04/03
 */
@Component
public class I18nUtils {

    private static MessageSource messageSource;

    public I18nUtils(MessageSource messageSource) {
        I18nUtils.messageSource = messageSource;
    }

    /**
     * 获取国际化消息
     *
     * @param code 消息key
     * @return 国际化消息
     */
    public static String getMessage(String code) {
        return getMessage(code, null);
    }

    /**
     * 获取国际化消息
     *
     * @param code 消息key
     * @param args 参数
     * @return 国际化消息
     */
    public static String getMessage(String code, Object[] args) {
        return getMessage(code, args, "");
    }

    /**
     * 获取国际化消息
     *
     * @param code           消息key
     * @param args           参数
     * @param defaultMessage 默认消息
     * @return 国际化消息
     */
    public static String getMessage(String code, Object[] args, String defaultMessage) {
        try {
            Locale locale = LocaleContextHolder.getLocale();
            return messageSource.getMessage(code, args, defaultMessage, locale);
        } catch (Exception e) {
            return defaultMessage;
        }
    }

    /**
     * 获取当前语言环境
     *
     * @return Locale
     */
    public static Locale getCurrentLocale() {
        return LocaleContextHolder.getLocale();
    }
}
