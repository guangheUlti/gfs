package com.guanghe.fs.service.alist;

import com.guanghe.fs.framework.common.utils.JsonUtils;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AList 协议响应封装。
 * <p>
 * AList 的所有业务错误都放在信封里（{@code code}）而不是 HTTP 状态码上，客户端（影视仓/TVBox、
 * rclone、AList 桌面端）普遍只读包体判成败。因此这里统一：<b>HTTP 恒 200</b>，
 * 由 {@code code} 表达 200 成功 / 400 参数错 / 401 未认证 / 403 只读 / 404 不存在 / 500 内部错误。
 */
public final class AlistResponse {

    /** 成功 */
    public static final int OK = 200;
    /** 参数错误 / 账号密码错 */
    public static final int BAD_REQUEST = 400;
    /** token 无效或缺失 */
    public static final int UNAUTHORIZED = 401;
    /** 只读模式拒绝写操作 */
    public static final int FORBIDDEN = 403;
    /** 路径不存在 */
    public static final int NOT_FOUND = 404;
    /** 内部错误 */
    public static final int ERROR = 500;

    private static final String SUCCESS = "success";

    private AlistResponse() {
    }

    /** 成功响应：{"code":200,"message":"success","data":...} */
    public static void ok(HttpServletResponse response, Object data) throws IOException {
        write(response, OK, SUCCESS, data);
    }

    /** 失败响应：{"code":xxx,"message":"...","data":null} */
    public static void error(HttpServletResponse response, int code, String message) throws IOException {
        write(response, code, message, null);
    }

    public static void write(HttpServletResponse response, int code, String message, Object data)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? SUCCESS : message);
        body.put("data", data);
        byte[] bytes = JsonUtils.toJsonString(body).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** 带固定文案的未认证（AList 原文案，客户端会据此触发重新登录） */
    public static void unauthorized(HttpServletResponse response) throws IOException {
        error(response, UNAUTHORIZED, "token is invalidated");
    }

    /** 只读模式拒绝 */
    public static void readOnly(HttpServletResponse response) throws IOException {
        error(response, FORBIDDEN, "AList service is in read-only mode");
    }
}
