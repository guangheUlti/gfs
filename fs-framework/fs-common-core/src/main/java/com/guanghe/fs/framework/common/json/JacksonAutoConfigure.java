package com.guanghe.fs.framework.common.json;

import com.guanghe.fs.framework.common.json.handler.BigNumberSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * @Author: guangheUlti
 * @Date: 2023/11/22 15:03
 */
@Slf4j
@Configuration
public class JacksonAutoConfigure {

    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public JsonMapperBuilderCustomizer customizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATETIME_FORMAT);
        return builder -> {
            // 全局配置序列化返回 JSON 处理
            builder.addModule(new SimpleModule()
                    .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter))
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter))
                    .addSerializer(Long.class, BigNumberSerializer.INSTANCE)
                    .addSerializer(BigInteger.class, BigNumberSerializer.INSTANCE)
                    .addSerializer(BigDecimal.class, ToStringSerializer.instance)
                    .addSerializer(Long.TYPE, BigNumberSerializer.instance));

            // 禁止将日期序列化为时间戳（使用配置的格式化字符串）
            builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
            // 遇到未知属性时不报错（提高容错性，避免字段新增导致反序列化失败）
            builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            builder.defaultTimeZone(TimeZone.getDefault());
            log.info("初始化 jackson 配置");
        };
    }
}
