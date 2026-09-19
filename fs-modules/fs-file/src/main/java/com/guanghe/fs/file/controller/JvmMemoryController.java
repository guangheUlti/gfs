package com.guanghe.fs.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.guanghe.fs.file.domain.vo.JvmMemoryVO;
import com.guanghe.fs.file.service.JvmMemoryService;
import com.guanghe.fs.framework.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JVM 运行时资源配置：最大堆内存的查看、保存与"重启生效"。
 * <p>
 * 保存与重启属于管理操作，需 storage:manage 权限。
 * 重启为异步行为：接口返回后约 1 秒进程退出，由看门狗按新配置重新拉起，
 * 期间会出现短暂的服务中断。
 *
 * @Author: guangheUlti
 */
@Slf4j
@RestController
@RequestMapping("/apis/system")
@RequiredArgsConstructor
@Tag(name = "系统运行时配置", description = "JVM 内存等运行期资源配置")
public class JvmMemoryController {

    private final JvmMemoryService jvmMemoryService;

    /** 更新请求体：maxMemoryMb 为 null 表示清除配置、使用 JVM 默认值 */
    public record JvmMemoryUpdateRequest(Integer maxMemoryMb) {
    }

    @GetMapping("/jvm-memory")
    @Operation(summary = "查询 JVM 最大内存配置",
            description = "返回配置文件中的配置值（MB）与运行实例实际最大堆内存")
    public Result<JvmMemoryVO> getConfig() {
        return Result.ok(jvmMemoryService.getConfig());
    }

    @PostMapping("/jvm-memory")
    @SaCheckPermission("storage:manage")
    @Operation(summary = "保存 JVM 最大内存配置",
            description = "仅落盘，重启后端后生效；maxMemoryMb 为 null 表示使用 JVM 默认值")
    public Result<Void> saveConfig(@RequestBody JvmMemoryUpdateRequest req) {
        jvmMemoryService.saveConfig(req.maxMemoryMb());
        return Result.ok();
    }

    @PostMapping("/jvm-memory/restart")
    @SaCheckPermission("storage:manage")
    @Operation(summary = "重启后端以应用 JVM 内存配置",
            description = "先落盘配置（可选），随后触发进程退出，由看门狗按最新配置重新拉起；期间会短暂中断")
    public Result<Void> restart(@RequestBody(required = false) JvmMemoryUpdateRequest req) {
        if (req != null) {
            jvmMemoryService.saveConfig(req.maxMemoryMb());
        }
        jvmMemoryService.triggerRestart();
        return Result.ok();
    }
}