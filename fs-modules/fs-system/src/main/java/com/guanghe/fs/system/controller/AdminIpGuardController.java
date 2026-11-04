package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.dto.IpGuardEditCmd;
import com.guanghe.fs.system.domain.vo.IpGuardConfigVO;
import com.guanghe.fs.system.service.IpGuardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IP 安全防护（白名单/黑名单互斥）：全局配置，仅系统管理员可读写
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Validated
@RestController
@RequestMapping("/apis/admin/ip-guard")
@RequiredArgsConstructor
@Tag(name = "IP 安全防护")
public class AdminIpGuardController {

    private final IpGuardService ipGuardService;

    @Operation(summary = "获取 IP 防护配置")
    @GetMapping
    public Result<IpGuardConfigVO> getConfig() {
        return Result.ok(ipGuardService.getConfig());
    }

    @Operation(summary = "保存 IP 防护配置")
    @PutMapping
    public Result<?> saveConfig(@Validated @RequestBody IpGuardEditCmd cmd) {
        ipGuardService.saveConfig(cmd);
        return Result.ok();
    }
}
