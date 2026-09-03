package com.guanghe.fs.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邀请详情视图对象
 *
 * @Author: guangheUlti
 * @Date: 2026/4/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitationDetailVO {

    /**
     * 邀请ID
     */
    private String id;

    /**
     * 工作空间ID
     */
    private String workspaceId;

    /**
     * 工作空间名称
     */
    private String workspaceName;

    /**
     * 邀请邮箱
     */
    private String email;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 邀请人名称
     */
    private String inviterName;

    /**
     * 邀请状态: 0-待接受 1-已接受 2-已过期 3-已取消
     */
    private Integer status;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 是否已过期
     */
    private Boolean expired;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 用户是否已存在（已注册）
     */
    private Boolean userExists;
}
