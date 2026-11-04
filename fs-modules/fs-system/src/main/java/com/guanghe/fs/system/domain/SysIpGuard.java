package com.guanghe.fs.system.domain;

import com.guanghe.fs.framework.orm.entity.BaseEntity;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * IP 安全防护配置（全局单行，固定主键 {@link #CONFIG_ID}）
 * <p>
 * 模式与两份名单放在一起整行读写：名单本身没有独立生命周期（不分享、不关联、不单独审计），
 * 拆成多行只会多出一套增删改接口和「改完模式忘了改名单」的不一致状态。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Data
@Table("sys_ip_guard")
@EqualsAndHashCode(callSuper = true)
public class SysIpGuard extends BaseEntity {

    /** 全局配置行的主键，固定值 */
    public static final String CONFIG_ID = "ip_guard";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 固定主键：全局只有一行
     */
    @Id(keyType = KeyType.None)
    private String id;

    /**
     * 防护模式：OFF 关闭 / WHITE 白名单 / BLACK 黑名单（互斥，只可能有一种名单生效）
     */
    private String mode;

    /**
     * 白名单条目 JSON 数组（IP 或 CIDR + 备注）
     */
    private String whiteList;

    /**
     * 黑名单条目 JSON 数组（IP 或 CIDR + 备注）
     */
    private String blackList;
}
