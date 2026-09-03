package com.guanghe.fs.framework.orm.listener;

import cn.hutool.core.util.ObjectUtil;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import com.guanghe.fs.framework.orm.helper.ListenerManager;
import com.mybatisflex.annotation.InsertListener;

import java.time.LocalDateTime;

/**
 * Entity实体类全局插入数据监听器
 *
 * @Author: guangheUlti
 * @Date: 2024/10/12 14:19
 */
public class EntityInsertListener implements InsertListener {

    @Override
    public void onInsert(Object entity) {
        try {
            if (ListenerManager.isDoInsertListener() && ObjectUtil.isNotNull(entity) && (entity instanceof BaseEntity)) {
                BaseEntity baseEntity = (BaseEntity) entity;

                LocalDateTime createTime = ObjectUtil.isNotNull(baseEntity.getCreatedAt())
                        ? baseEntity.getCreatedAt() : LocalDateTime.now();

                baseEntity.setCreatedAt(createTime);
                baseEntity.setUpdatedAt(createTime);
            }
        } catch (Exception e) {
            throw new BusinessException("全局插入数据监听器注入异常 => " + e.getMessage());
        }
    }
}
