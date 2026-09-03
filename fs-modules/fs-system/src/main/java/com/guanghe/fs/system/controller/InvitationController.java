package com.guanghe.fs.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.vo.InvitationDetailVO;
import com.guanghe.fs.system.service.SysWorkspaceInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 邀请控制器（公开接口）
 *
 * @Author: guangheUlti
 * @Date: 2026/4/14
 */
@Validated
@RestController
@RequestMapping("/apis/invitation")
@RequiredArgsConstructor
@Tag(name = "邀请管理（公开）")
public class InvitationController {

    private final SysWorkspaceInvitationService invitationService;

    @Operation(summary = "验证邀请令牌")
    @GetMapping("/verify/{token}")
    public Result<InvitationDetailVO> verifyInvitation(@PathVariable String token) {
        InvitationDetailVO result = invitationService.verifyInvitation(token);
        return Result.ok(result);
    }

    @Operation(summary = "接受邀请")
    @PostMapping("/accept/{token}")
    public Result<Void> acceptInvitation(@PathVariable String token) {
        String userId = StpUtil.getLoginIdAsString();
        invitationService.acceptInvitation(token, userId);
        return Result.ok();
    }
}
