package com.guanghe.fs.system.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.system.domain.SysPermission;
import com.guanghe.fs.system.domain.vo.SysPermissionVO;

import java.util.List;

/**
 * 权限服务接口
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
public interface SysPermissionService extends IService<SysPermission> {

    /**
     * 获取所有权限
     *
     * @return 所有权限
     */
    List<SysPermission> getAll();

    /**
     * 获取权限列表
     *
     * @return 权限列表
     */
    List<SysPermissionVO> getList();
}
