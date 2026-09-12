package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.dto.FeatureToggleEditCmd;

import java.util.Map;

/**
 * 功能开关业务接口
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
public interface SysFeatureToggleService {

    /**
     * 查询全部功能开关（未落库的按关闭返回）
     *
     * @return 功能标识 -> 是否开启
     */
    Map<String, Boolean> listToggles();

    /**
     * 修改功能开关（仅系统管理员）
     *
     * @param cmd 功能开关修改命令
     */
    void updateToggles(FeatureToggleEditCmd cmd);
}
