package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证入口
 * <p>
 * 凭证只通过响应体的 {@code accessToken} 下发，由前端注入请求头或查询参数。
 * 不再下发 Authorization Cookie：token-prefix 为 Bearer 时，Cookie 里的裸 token
 * 过不了 Sa-Token 的前缀校验，下发的是一个永远不被接受的死凭证，容易误导后续开发。
 */
@RestController
@RequestMapping("/apis/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<?> doLogin(@Valid @RequestBody LoginCmd cmd) {
        return Result.ok(authService.doLogin(cmd));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<?> logout() {
        authService.logout();
        return Result.ok();
    }
}
