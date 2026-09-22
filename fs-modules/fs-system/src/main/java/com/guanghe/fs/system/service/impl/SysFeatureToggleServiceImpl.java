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

    private final SysUserService sysUserService;

    @Override
    @Cacheable(value = "featureToggles", key = "'all'")
    public Map<String, Boolean> listToggles() {
        Map<String, Boolean> result = new HashMap<>();
        FeatureKeys.ALL.forEach(key -> result.put(key, false));
        // 回收站缺省开启：未配置时保持历史行为（删除进回收站）
        result.put(FeatureKeys.RECYCLE_BIN, Boolean.TRUE);
        this.list().forEach(toggle -> {
            if (FeatureKeys.ALL.contains(toggle.getFeatureKey())) {
                result.put(toggle.getFeatureKey(), Boolean.TRUE.equals(toggle.getEnabled()));
            }
        });
        return result;
    }

    @Override
    @CacheEvict(value = "featureToggles", key = "'all'")
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
