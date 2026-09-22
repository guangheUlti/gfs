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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    @Override
    @Cacheable(value = "featureToggles", key = "'all'")
    public Map<String, Boolean> listToggles() {
        Map<String, Boolean> result = new HashMap<>();
        // 全部开关缺省关闭（含回收站、分享）：按需在「设置-功能开关」中开启
        FeatureKeys.ALL.forEach(key -> result.put(key, false));
        this.list().forEach(toggle -> {
            if (FeatureKeys.ALL.contains(toggle.getFeatureKey())) {
                result.put(toggle.getFeatureKey(), Boolean.TRUE.equals(toggle.getEnabled()));
            }
        });
        return result;
    }

    /**
     * 功能开关对所有登录用户开放修改：开关影响的是普通用户的日常功能
     * （回收站/分享/收藏等），管理员与普通用户需求一致，无需限制
     */
    @Override
    @CacheEvict(value = "featureToggles", key = "'all'")
    public void updateToggles(FeatureToggleEditCmd cmd) {

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
