package com.guanghe.fs.storage.service.impl;

import com.guanghe.fs.storage.domain.StoragePlatform;
import com.guanghe.fs.storage.domain.vo.StoragePlatformVO;
import com.guanghe.fs.storage.mapper.StoragePlatformMapper;
import com.guanghe.fs.storage.service.StoragePlatformService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

import static com.guanghe.fs.storage.domain.table.StoragePlatformTableDef.STORAGE_PLATFORM;
import static com.guanghe.fs.storage.plugin.core.utils.StorageUtils.platformDisplayOrder;

/**
 * 存储平台业务接口实现
 *
 * @Author: guangheUlti
 * @Date: 2024/10/25 14:38
 */
@Service
@RequiredArgsConstructor
public class StoragePlatformServiceImpl extends ServiceImpl<StoragePlatformMapper, StoragePlatform> implements StoragePlatformService {

    private final Converter converter;


    @Override
    public List<StoragePlatformVO> getList() {
        List<StoragePlatform> storagePlatforms = this.list();
        List<StoragePlatformVO> result = converter.convert(storagePlatforms, StoragePlatformVO.class);
        result.sort(Comparator.comparingInt(vo -> platformDisplayOrder(vo.getIdentifier())));
        return result;
    }


    @Override
    @Cacheable(value = "storagePlatform", key = "#identifier", unless = "#result == null")
    public StoragePlatform getStoragePlatformByIdentifier(String identifier) {
        return this.getOne(new QueryWrapper().where(STORAGE_PLATFORM.IDENTIFIER.eq(identifier)));
    }
}
