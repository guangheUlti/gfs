package com.guanghe.fs.framework.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误编码
 *
 * @Author: guangheUlti
 * @Date: 2023/11/21 18:14
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 认证授权相关 (401)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED.value(), "未经授权，请进行身份验证。"),
    NOT_TOKEN(HttpStatus.UNAUTHORIZED.value(), "未提供认证令牌。"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED.value(), "认证令牌已过期。"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED.value(), "无效的认证令牌。"),
    NO_PREFIX_MESSAGE_TOKEN(HttpStatus.UNAUTHORIZED.value(), "未按照指定前缀提交token。"),
    KICK_OUT_TOKEN(HttpStatus.UNAUTHORIZED.value(), "令牌已被系统强制下线。"),
    REPLACED_TOKEN(HttpStatus.UNAUTHORIZED.value(), "令牌已被另一个登录方式所取代。"),

    // 权限相关 (403)
    FORBIDDEN(HttpStatus.FORBIDDEN.value(), "没有权限，禁止访问");
//
//    // 客户端错误 (400)
//    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "参数错误"),
//
//    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "缺少必要参数"),
//
//    DUPLICATE_RESOURCE(HttpStatus.BAD_REQUEST, "DUPLICATE_RESOURCE", "资源已存在"),
//
//    // 资源相关 (404)
//    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在"),
//
//    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"),
//
//    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在"),
//
//    // 业务逻辑错误 (422)
//    BUSINESS_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_ERROR", "业务处理失败"),
//    OPERATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATION_NOT_ALLOWED", "操作不被允许"),
//
//    //限流错误 (429)
//    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Rate limit exceeded, please try again later."),
//
//    // 服务器错误 (500)
//    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Internal server error, please try again later."),
//
//    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "数据库操作失败"),
//
//    EXTERNAL_SERVICE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "EXTERNAL_SERVICE_ERROR", "外部服务调用失败");

    private final int code;
    private final String msg;
}
