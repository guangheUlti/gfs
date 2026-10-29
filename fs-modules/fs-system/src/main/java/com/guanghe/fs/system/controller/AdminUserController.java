package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.log.constant.OperationType;
import com.guanghe.fs.log.service.SysOperationLogService;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.AdminResetPasswordCmd;
import com.guanghe.fs.system.domain.dto.AdminUserCreateCmd;
import com.guanghe.fs.system.domain.dto.AdminUserQry;
import com.guanghe.fs.system.domain.dto.AdminUserStatusCmd;
import com.guanghe.fs.system.domain.dto.AdminUserUpdateCmd;
import com.guanghe.fs.system.domain.vo.AdminUserVO;
import com.guanghe.fs.system.domain.vo.LockedUserVO;
import com.guanghe.fs.system.domain.vo.PendingUserVO;
import com.guanghe.fs.system.domain.vo.UserDataSummaryVO;
import com.guanghe.fs.system.service.AdminUserManageService;
import com.guanghe.fs.system.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统级用户管理（账号增删改查、注册审核等全局操作，仅系统管理员可用）
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

    private final AdminUserManageService adminUserManageService;

    private final SysOperationLogService operationLogService;

    @Operation(summary = "账号列表（分页，含在线终端与锁定状态）")
    @GetMapping
    public PageResult<AdminUserVO> list(AdminUserQry qry) {
        return adminUserManageService.listUsers(qry);
    }

    @Operation(summary = "手动创建用户（免审核，创建即可登录）")
    @PostMapping
    public Result<?> create(@Validated @RequestBody AdminUserCreateCmd cmd) {
        userService.createUserByAdmin(cmd);
        operationLogService.recordSuccess(
                OperationType.CREATE_USER,
                "管理员创建用户",
                "USER",
                cmd.getUsername(),
                null,
                null
        );
        return Result.ok();
    }

    @Operation(summary = "修改账号资料（昵称）")
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable String id,
                            @Validated @RequestBody AdminUserUpdateCmd cmd) {
        String targetName = usernameOf(id);
        adminUserManageService.updateProfile(id, cmd);
        operationLogService.recordSuccess(
                OperationType.UPDATE_USER,
                "修改用户资料",
                "USER",
                id,
                targetName,
                null
        );
        return Result.ok();
    }

    @Operation(summary = "启用/禁用账号（禁用同时强制下线）")
    @PutMapping("/{id}/status")
    public Result<?> updateStatus(@PathVariable String id,
                                  @Validated @RequestBody AdminUserStatusCmd cmd) {
        String targetName = usernameOf(id);
        boolean enabled = Boolean.TRUE.equals(cmd.getEnabled());
        adminUserManageService.setEnabled(id, enabled);
        operationLogService.recordSuccess(
                enabled ? OperationType.ENABLE_USER : OperationType.DISABLE_USER,
                enabled ? "启用用户" : "禁用用户",
                "USER",
                id,
                targetName,
                enabled ? null : "已强制下线全部会话"
        );
        return Result.ok();
    }

    @Operation(summary = "重置账号密码（同时强制下线）")
    @PutMapping("/{id}/password")
    public Result<?> resetPassword(@PathVariable String id,
                                   @Validated @RequestBody AdminResetPasswordCmd cmd) {
        String targetName = usernameOf(id);
        adminUserManageService.resetPassword(id, cmd);
        operationLogService.recordSuccess(
                OperationType.RESET_USER_PASSWORD,
                "重置用户密码",
                "USER",
                id,
                targetName,
                "已强制下线全部会话"
        );
        return Result.ok();
    }

    @Operation(summary = "账号残留数据概览（删除前确认）")
    @GetMapping("/{id}/data")
    public Result<UserDataSummaryVO> dataSummary(@PathVariable String id) {
        return Result.ok(adminUserManageService.getUserDataSummary(id));
    }

    @Operation(summary = "删除账号并彻底清除其全部数据")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable String id) {
        String targetName = usernameOf(id);
        adminUserManageService.deleteUser(id);
        operationLogService.recordSuccess(
                OperationType.DELETE_USER,
                "删除用户并清除数据",
                "USER",
                id,
                targetName,
                "已清理文件、分享、收藏、收集、传输任务等数据"
        );
        return Result.ok();
    }

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

    @Operation(summary = "被锁定账号列表（连续登录失败触发防暴力锁定）")
    @GetMapping("/locked")
    public Result<List<LockedUserVO>> listLocked() {
        return Result.ok(userService.listLockedUsers());
    }

    @Operation(summary = "解除账号锁定（清除所有 IP 的锁定与失败计数）")
    @PutMapping("/locked/{username}/unlock")
    public Result<?> unlock(@PathVariable String username) {
        userService.unlockUser(username);
        operationLogService.recordSuccess(
                OperationType.UNLOCK_USER,
                "解除账号锁定",
                "USER",
                username,
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

    /** 审计日志需要记录目标用户名；账号不存在时交给服务层抛统一异常 */
    private String usernameOf(String id) {
        SysUser user = userService.getById(id);
        return user == null ? null : user.getUsername();
    }
}
