package com.guanghe.fs.framework.common.utils;

/**
 * 错误信息工具类
 * 用于将技术性错误信息转换为用户友好的提示
 *
 * @author guanghe
 */
public class ErrorMessageUtils {

    /**
     * 提取用户友好的错误信息
     * 将技术性错误信息转换为用户可理解的提示
     *
     * @param message 原始错误信息
     * @return 用户友好的错误信息
     */
    public static String extractUserFriendlyMessage(String message) {
        if (message == null) {
            return "操作失败，请稍后重试";
        }

        // 连接被拒绝
        if (message.contains("Connection refused") || message.contains("connect timed out")) {
            return "无法连接到存储服务，请检查存储配置";
        }

        // 连接超时
        if (message.contains("timeout") || message.contains("timed out")) {
            return "连接存储服务超时，请检查网络或稍后重试";
        }

        // 认证失败
        if (message.contains("403") || message.contains("Forbidden") ||
                message.contains("Access Denied") || message.contains("InvalidAccessKeyId")) {
            return "存储服务认证失败，请检查访问密钥配置";
        }

        // 未授权
        if (message.contains("401") || message.contains("Unauthorized")) {
            return "存储服务认证失败，请检查访问密钥配置";
        }

        // 找不到资源
        if (message.contains("404") || message.contains("Not Found") ||
                message.contains("NoSuchBucket") || message.contains("NoSuchKey")) {
            return "存储资源不存在，请检查配置";
        }

        // 网络错误
        if (message.contains("UnknownHostException") || message.contains("No such host")) {
            return "无法解析存储服务地址，请检查配置";
        }

        // SSL/TLS 错误
        if (message.contains("SSL") || message.contains("certificate")) {
            return "存储服务安全连接失败，请检查配置";
        }

        // 如果消息中包含中文，说明已经是用户友好的消息
        if (message.matches(".*[\u4e00-\u9fa5]+.*")) {
            // 去掉技术细节部分（冒号后的内容）
            int colonIndex = message.indexOf(":");
            if (colonIndex > 0 && colonIndex < 100) {
                String beforeColon = message.substring(0, colonIndex);
                // 如果冒号前的部分包含中文，只返回这部分
                if (beforeColon.matches(".*[\u4e00-\u9fa5]+.*")) {
                    return beforeColon;
                }
            }
            
            // 去掉 "Unable to execute HTTP request" 等技术前缀
            if (message.contains("Unable to execute HTTP request")) {
                int requestIndex = message.indexOf("Unable to execute HTTP request");
                if (requestIndex > 0) {
                    return message.substring(0, requestIndex).trim();
                }
            }
            
            return message;
        }

        // 默认返回通用错误信息
        return "操作失败，请稍后重试";
    }

    /**
     * 提取用户友好的错误信息（从异常对象）
     *
     * @param e 异常对象
     * @return 用户友好的错误信息
     */
    public static String extractUserFriendlyMessage(Exception e) {
        if (e == null) {
            return "操作失败，请稍后重试";
        }
        return extractUserFriendlyMessage(e.getMessage());
    }
}
