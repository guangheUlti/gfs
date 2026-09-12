package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.dto.FeatureToggleEditCmd;
import com.guanghe.fs.system.service.SysFeatureToggleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 功能开关：查询对所有登录用户开放（前端按此显隐侧边栏菜单），修改仅系统管理员
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
@Validated
@RestController
@RequestMapping("/apis/feature")
@RequiredArgsConstructor
@Tag(name = "功能开关")
public class FeatureToggleController {

    private final SysFeatureToggleService featureToggleService;

    @Operation(summary = "获取功能开关")
    @GetMapping("/toggles")
    public Result<Map<String, Boolean>> listToggles() {
        return Result.ok(featureToggleService.listToggles());
    }

    @Operation(summary = "修改功能开关")
    @PutMapping("/toggles")
    public Result<?> updateToggles(@Validated @RequestBody FeatureToggleEditCmd cmd) {
        featureToggleService.updateToggles(cmd);
        return Result.ok();
    }
}
