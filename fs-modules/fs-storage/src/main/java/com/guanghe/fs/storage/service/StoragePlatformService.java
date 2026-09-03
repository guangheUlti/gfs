package com.guanghe.fs.storage.service;

import com.guanghe.fs.storage.domain.StoragePlatform;
import com.guanghe.fs.storage.domain.vo.StoragePlatformVO;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 存储平台业务接口
 *
 * @Author: guangheUlti
 * @Date: 2024/10/25 14:37
 */
public interface StoragePlatformService extends IService<StoragePlatform> {

    /**
     * 查询所有存储平台列表
     *
     * @return
     */
    List<StoragePlatformVO> getList();


    /**
     * 根据标识符查询存储平台
     *
     * @param identifier
     * @return
     */
    StoragePlatform getStoragePlatformByIdentifier(String identifier);
}
