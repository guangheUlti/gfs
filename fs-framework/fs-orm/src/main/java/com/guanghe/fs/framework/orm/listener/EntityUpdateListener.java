package com.guanghe.fs.framework.orm.listener;

import cn.hutool.core.util.ObjectUtil;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import com.guanghe.fs.framework.orm.helper.ListenerManager;
import com.mybatisflex.annotation.UpdateListener;

import java.time.LocalDateTime;

/**
 * Entity实体类全局更新数据监听器
 */
public class EntityUpdateListener implements UpdateListener {
    @Override
    public void onUpdate(Object entity) {
        try {
            if (ListenerManager.isDoUpdateListener() && ObjectUtil.isNotNull(entity) && (entity instanceof BaseEntity)) {
                BaseEntity baseEntity = (BaseEntity) entity;
                baseEntity.setUpdatedAt(LocalDateTime.now());
            }
        } catch (Exception e) {
            throw new BusinessException("全局更新数据监听器注入异常 => " + e.getMessage());
        }
    }
}
