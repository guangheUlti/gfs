package com.guanghe.fs.storage.mapper;

import com.guanghe.fs.storage.domain.StoragePlatform;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 存储平台 Mapper 接口
 *
 * @Author: guangheUlti
 * @Date: 2024/10/25 14:36
 */
public interface StoragePlatformMapper extends BaseMapper<StoragePlatform> {

    /**
     * 根据平台标识符查询存储平台
     *
     * @param identifier 平台标识符
     * @return 存储平台实体，如果不存在返回null
     */
    @Select("SELECT * FROM storage_platform WHERE identifier = #{identifier}")
    StoragePlatform selectByIdentifier(@Param("identifier") String identifier);
}
