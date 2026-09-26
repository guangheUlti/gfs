package com.guanghe.fs.service.domain;

import com.guanghe.fs.framework.orm.entity.BaseEntity;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * OSS 对外服务访问密钥（每用户多把，SigV4 鉴权用）。
 * <p>
 * Secret Key 仅创建时明文返回一次，库中存 AES-GCM 密文（SigV4 验签需还原明文）；吊销后立即失效。
 */
@Data
@Table("oss_access_keys")
@EqualsAndHashCode(callSuper = true)
public class OssAccessKey extends BaseEntity {

    @Id(keyType = KeyType.None)
    private String id;

    /** 所属用户 ID */
    private String userId;

    /** Access Key（对外可见） */
    private String accessKey;

    /** Secret Key AES-GCM 密文（Base64） */
    private String secretKey;

    /** 备注 */
    private String remark;

    /** 状态 0正常 1吊销 */
    private Integer status;

    /** 最后使用时间（鉴权成功时刷新，节流 1 分钟） */
    private LocalDateTime lastUsedAt;
}
