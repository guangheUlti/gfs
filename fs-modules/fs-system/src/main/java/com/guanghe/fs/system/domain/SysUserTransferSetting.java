package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户传输设置实体类
 *
 * @Author: guangheUlti
 * @Date: 2025/11/11 14:35
 */
@Data
@Table("sys_user_transfer_setting")
@EqualsAndHashCode(callSuper = true)
public class SysUserTransferSetting extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 下载速率限制 单位：MB/S
     */
    private Integer downloadSpeedLimit;

    /**
     * 并发上传数量
     */
    private Integer concurrentUploadQuantity;

    /**
     * 并发下载数量
     */
    private Integer concurrentDownloadQuantity;

    /**
     * 分片大小（字节），默认 5MB
     */
    private Long chunkSize;


    public static SysUserTransferSetting init(String userId) {
        SysUserTransferSetting sysUserTransferSetting = new SysUserTransferSetting();
        sysUserTransferSetting.setUserId(userId);
        sysUserTransferSetting.setDownloadSpeedLimit(-1);
        sysUserTransferSetting.setConcurrentUploadQuantity(3);
        sysUserTransferSetting.setConcurrentDownloadQuantity(3);
        sysUserTransferSetting.setChunkSize(5L * 1024 * 1024); // 默认 5MB
        return sysUserTransferSetting;
    }
}
