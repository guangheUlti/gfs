package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作空间成员表
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Data
@Table("sys_workspace_member")
public class SysWorkspaceMember implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 工作空间ID
     */
    private String workspaceId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 该成员在此工作空间的角色ID
     */
    private Long roleId;

    /**
     * 加入时间
     */
    private LocalDateTime joinedAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
