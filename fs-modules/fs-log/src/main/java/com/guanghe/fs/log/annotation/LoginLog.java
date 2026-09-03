package com.guanghe.fs.log.annotation;

import java.lang.annotation.*;

/**
 * 登录日志注解
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:35
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginLog {

    /**
     * 操作描述
     */
    String value() default "用户登录";

    /**
     * 是否记录参数
     */
    boolean includeArgs() default false;

    /**
     * 是否记录返回值
     */
    boolean includeResult() default false;
}
