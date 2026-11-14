package com.guanghe.fs.file.mapper;

import com.guanghe.fs.file.domain.FileInfo;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件资源Mapper接口
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:30
 */
@Mapper
public interface FileInfoMapper extends BaseMapper<FileInfo> {

    /**
     * 统计某存储底座下已存文件占用（SUM 下推到库，避免把十万级行读进 JVM）
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(size), 0) FROM file_info
            WHERE is_deleted = 0 AND is_dir = 0
            <choose>
              <when test="settingId != null and settingId != ''">AND storage_platform_setting_id = #{settingId}</when>
              <otherwise>AND storage_platform_setting_id IS NULL</otherwise>
            </choose>
            </script>
            """)
    long sumStoredSize(@Param("settingId") String settingId);

    /**
     * 按天聚合上传字节数：返回 d(yyyy-MM-dd) -> total
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(upload_time, '%Y-%m-%d') AS d, COALESCE(SUM(size), 0) AS total
            FROM file_info
            WHERE is_deleted = 0 AND is_dir = 0
              AND upload_time BETWEEN #{start} AND #{end}
            <choose>
              <when test="settingId != null and settingId != ''">AND storage_platform_setting_id = #{settingId}</when>
              <otherwise>AND storage_platform_setting_id IS NULL</otherwise>
            </choose>
            GROUP BY DATE_FORMAT(upload_time, '%Y-%m-%d')
            </script>
            """)
    List<Map<String, Object>> sumUploadSizeGroupByDay(@Param("settingId") String settingId,
                                                      @Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);
}
