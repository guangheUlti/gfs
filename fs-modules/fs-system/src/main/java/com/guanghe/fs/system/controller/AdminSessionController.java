package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.vo.OnlineUserVO;
import com.guanghe.fs.system.service.SessionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 登录管理（查看在线会话、强制下线），仅系统管理员可用
 * <p>
 * 权限判定在 Service 层走 {@code SysUserService#assertSuperAdmin}，与注册审核保持一致。
 * 踢人属于破坏性操作，审计日志在 Service 内于执行前记录（避开自踢后身份丢失）。
 *
 * @Author: guangheUlti
 * @Date: 2026/9/5
 */
@Validated
@RestController
@RequestMapping("/apis/admin/sessions")
@RequiredArgsConstructor
@Tag(name = "登录管理")
public class AdminSessionController {

    private final SessionAdminService sessionAdminService;

    @Operation(summary = "在线登录用户及其终端列表")
    @GetMapping
    public Result<List<OnlineUserVO>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(sessionAdminService.listOnlineSessions(keyword));
    }

    @Operation(summary = "强制该用户的全部终端下线")
    @DeleteMapping("/{loginId}")
    public Result<Integer> kickoutUser(@PathVariable String loginId) {
        return Result.ok(sessionAdminService.kickoutUser(loginId));
    }

    @Operation(summary = "强制指定终端下线")
    @DeleteMapping("/{loginId}/terminals/{index}")
    public Result<?> kickoutTerminal(@PathVariable String loginId,
                                     @PathVariable int index,
                                     @RequestParam(required = false) String tokenTail) {
        sessionAdminService.kickoutTerminal(loginId, index, tokenTail);
        return Result.ok();
    }
}
