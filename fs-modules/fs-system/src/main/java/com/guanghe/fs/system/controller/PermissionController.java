package com.guanghe.fs.system.controller;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.system.domain.vo.SysPermissionVO;
import com.guanghe.fs.system.service.SysPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/apis/permission")
@RequiredArgsConstructor
@Tag(name = "权限管理")
public class PermissionController {

    private final SysPermissionService sysPermissionService;

    @Operation(summary = "获取权限列表")
    @GetMapping("/list")
    public Result<List<SysPermissionVO>> getList() {
        List<SysPermissionVO> permissions = sysPermissionService.getList();
        return Result.ok(permissions);
    }
}
