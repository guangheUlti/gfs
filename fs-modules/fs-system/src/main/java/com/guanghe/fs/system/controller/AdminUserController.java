package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.log.constant.OperationType;
import com.guanghe.fs.log.service.SysOperationLogService;
import com.guanghe.fs.system.domain.vo.PendingUserVO;
import com.guanghe.fs.system.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统级用户管理（注册审核等全局操作，仅系统管理员可用）
 *
 * @Author: guangheUlti
 * @Date: 2026/9/1
 */
@Validated
@RestController
@RequestMapping("/apis/admin/users")
@RequiredArgsConstructor
@Tag(name = "系统用户管理")
public class AdminUserController {

    private final SysUserService userService;

    private final SysOperationLogService operationLogService;

    @Operation(summary = "待审核用户列表")
    @GetMapping("/pending")
    public Result<List<PendingUserVO>> listPending() {
        return Result.ok(userService.listPendingUsers());
    }

    @Operation(summary = "审核通过")
    @PutMapping("/{id}/approve")
    public Result<?> approve(@PathVariable String id) {
        userService.approveUser(id);
        operationLogService.recordSuccess(
                OperationType.APPROVE_USER,
                "审核通过用户",
                "USER",
                id,
                null,
                null
        );
        return Result.ok();
    }

    @Operation(summary = "拒绝注册申请")
    @PutMapping("/{id}/reject")
    public Result<?> reject(@PathVariable String id) {
        userService.rejectUser(id);
        operationLogService.recordSuccess(
                OperationType.REJECT_USER,
                "拒绝用户注册申请",
                "USER",
                id,
                null,
                null
        );
        return Result.ok();
    }
}
