package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 功能开关修改命令
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
@Data
public class FeatureToggleEditCmd {

    /**
     * 功能标识 -> 是否开启
     */
    @NotEmpty
    private Map<String, Boolean> toggles;
}
