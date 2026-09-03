package com.guanghe.fs.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 国际化测试控制器
 *
 * @Author: guangheUlti
 * @Date: 2026/04/03
 */
@Tag(name = "国际化测试")
@RestController
@RequestMapping("/apis/i18n")
public class I18nTestController {

    @Operation(summary = "测试国际化消息")
    @GetMapping("/test")
    public Result<String> test() {
        String message = I18nUtils.getMessage("success");
        return Result.ok(message);
    }

    @Operation(summary = "测试国际化异常")
    @GetMapping("/test-error")
    public Result<String> testError() {
        throw new RuntimeException("测试异常");
    }
}
