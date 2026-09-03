package com.guanghe.fs.file.mapper;

import com.mybatisflex.core.BaseMapper;
import com.guanghe.fs.file.domain.FileShareItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分享文件关联数据访问层接口
 *
 * @Author: guangheUlti
 * @Date: 2025/10/29 15:13
 */
@Mapper
public interface FileShareItemMapper extends BaseMapper<FileShareItem> {
}
