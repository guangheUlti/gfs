package com.guanghe.fs.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 国际化配置
 *
 * @Author: guangheUlti
 * @Date: 2026/03/13
 */
@Configuration
public class I18nConfig {

    /**
     * 支持的语言列表
     */
    private static final List<Locale> SUPPORTED_LOCALES = Arrays.asList(
            Locale.SIMPLIFIED_CHINESE,
            Locale.US
    );

    /**
     * 自定义 LocaleResolver，从请求头 lang 中获取语言
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                // 从请求头获取 lang 参数
                String lang = request.getHeader("lang");
                
                if (StringUtils.hasText(lang)) {
                    try {
                        // 解析语言标签，支持 zh-CN, en-US 等格式
                        Locale locale = Locale.forLanguageTag(lang.replace("_", "-"));
                        // 检查是否在支持的语言列表中
                        if (SUPPORTED_LOCALES.contains(locale)) {
                            return locale;
                        }
                    } catch (Exception e) {
                        // 解析失败，使用默认语言
                    }
                }
                
                // 默认返回中文
                return Locale.SIMPLIFIED_CHINESE;
            }
        };
    }
}
