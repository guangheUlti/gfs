package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.SysUserTransferSetting;
import com.guanghe.fs.system.domain.dto.*;
import com.guanghe.fs.system.domain.vo.SysUserVO;
import com.guanghe.fs.system.service.SysUserService;
import com.guanghe.fs.system.service.SysUserTransferSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户控制器
 *
 * @Author: guangheUlti
 * @Date: 2024/6/18 8:51
 */
@Validated
@RestController
@RequestMapping("/apis/user")
@RequiredArgsConstructor
@Tag(name = "用户管理")
public class UserController {

    private final SysUserService userService;

    private final SysUserTransferSettingService userTransferSettingService;

    @Operation(summary = "获取用户详细信息")
    @GetMapping("/info")
    public Result<SysUserVO> getDetail() {
        SysUserVO user = userService.getDetail();
        return Result.ok(user);
    }

    @Operation(summary = "注册用户")
    @PostMapping("/register")
    public Result<?> register(@Validated @RequestBody UserRegisterCmd cmd) {
        userService.register(cmd);
        return Result.ok();
    }

    @Operation(summary = "编辑用户")
    @PutMapping("/info")
    public Result<?> editUserInfo(@Validated @RequestBody UserEditInfoCmd cmd) {
        userService.editUserInfo(cmd);
        return Result.ok();
    }

    @Operation(summary = "头像上传")
    @PutMapping("/avatar")
    public Result<?> uploadAvatar(@RequestParam MultipartFile file) {
        userService.uploadAvatar(file);
        return Result.ok();
    }

    @Operation(summary = "设置密码")
    @PostMapping("/password")
    public Result<?> setPassword(@Validated @RequestBody PasswordAddCmd cmd) {
        userService.setPassword(cmd);
        return Result.ok();
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public Result<?> resetPassword(@Validated @RequestBody PasswordEditCmd cmd) {
        userService.updatePassword(cmd);
        return Result.ok();
    }

    @Operation(summary = "获取用户传输配置")
    @GetMapping("/transfer/setting")
    public Result<SysUserTransferSetting> getUserTransferSetting() {
        SysUserTransferSetting userTransferSetting = userTransferSettingService.getByUser();
        return Result.ok(userTransferSetting);
    }

    @Operation(summary = "修改用户传输配置")
    @PutMapping("/transfer/setting")
    public Result<SysUserTransferSetting> updateUserTransferSetting(@Validated @RequestBody UserTransferSettingEditCmd cmd) {
        userTransferSettingService.updateUserTransferSetting(cmd);
        return Result.ok();
    }

}
