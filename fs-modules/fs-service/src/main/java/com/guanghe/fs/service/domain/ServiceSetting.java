package com.guanghe.fs.service.domain;

import com.guanghe.fs.framework.orm.entity.BaseEntity;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对外文件服务配置表（WebDAV / SFTP，每协议一行）
 */
@Data
@Table("service_settings")
@EqualsAndHashCode(callSuper = true)
public class ServiceSetting extends BaseEntity {

    /** 固定主键：svc-webdav / svc-sftp */
    @Id(keyType = KeyType.None)
    private String id;

    /** 服务类型：webdav / sftp */
    private String serviceType;

    /** 是否启用 0：否 1：是 */
    private Integer enabled;

    /** 监听端口（webdav 复用 HTTP 80 端口，此列为空） */
    private Integer port;

    /** 监听地址，默认 0.0.0.0 */
    private String bindAddress;

    /** 扩展配置 JSON（如 sftp 主机密钥路径） */
    private String configData;

    /** 备注 */
    private String remark;

    /** 是否逻辑删除 0未删除 1已删除 */
    private Integer deleted;
}
