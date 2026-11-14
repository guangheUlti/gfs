package com.guanghe.fs.system.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.system.constant.FeatureKeys;
import com.guanghe.fs.system.domain.SysFeatureToggle;
import com.guanghe.fs.system.domain.dto.FeatureToggleEditCmd;
import com.guanghe.fs.system.mapper.SysFeatureToggleMapper;
import com.guanghe.fs.system.service.SysFeatureToggleService;
import com.guanghe.fs.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static com.guanghe.fs.system.domain.table.SysFeatureToggleTableDef.SYS_FEATURE_TOGGLE;

/**
 * 功能开关业务实现
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
@Service
@RequiredArgsConstructor
public class SysFeatureToggleServiceImpl extends ServiceImpl<SysFeatureToggleMapper, SysFeatureToggle> implements SysFeatureToggleService {

    private final SysUserService sysUserService;

    /**
     * 不做缓存：开关表只有几行，查询成本可忽略；而缓存的整张 Map 在新增开关键后
     * 会缺少字段（旧版本写进 Redis 的缓存没有过期时间），导致新开关被误判为关闭。
     */
    @Override
    public Map<String, Boolean> listToggles() {
        Map<String, Boolean> result = new HashMap<>();
        // 未配置的开关按 FeatureKeys.DEFAULT_ENABLED 兜底：回收站、分享等默认关闭，注册默认开启
        FeatureKeys.ALL.forEach(key -> result.put(key, FeatureKeys.DEFAULT_ENABLED.contains(key)));
        this.list().forEach(toggle -> {
            if (FeatureKeys.ALL.contains(toggle.getFeatureKey())) {
                result.put(toggle.getFeatureKey(), Boolean.TRUE.equals(toggle.getEnabled()));
            }
        });
        return result;
    }

    /**
     * 功能开关为全局配置（影响所有用户），仅系统管理员可修改；
     * 查询接口对所有登录用户开放（前端需按开关显隐入口）
     */
    @Override
    public void updateToggles(FeatureToggleEditCmd cmd) {
        sysUserService.assertSuperAdmin();

        for (String featureKey : cmd.getToggles().keySet()) {
            if (!FeatureKeys.ALL.contains(featureKey)) {
                throw new BusinessException("未知的功能开关: " + featureKey);
            }
        }

        for (Map.Entry<String, Boolean> entry : cmd.getToggles().entrySet()) {
            SysFeatureToggle toggle = this.getOne(
                    new QueryWrapper().where(SYS_FEATURE_TOGGLE.FEATURE_KEY.eq(entry.getKey()))
            );
            if (toggle == null) {
                toggle = new SysFeatureToggle();
                toggle.setFeatureKey(entry.getKey());
                toggle.setEnabled(entry.getValue());
                this.save(toggle);
            } else {
                toggle.setEnabled(entry.getValue());
                this.updateById(toggle);
            }
        }
    }
}
