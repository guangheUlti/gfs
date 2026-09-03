package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.guanghe.fs.framework.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作空间成员 VO
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
@Schema(description = "工作空间成员信息")
public class WorkspaceMemberVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "成员记录ID")
    private String id;

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "工作空间ID")
    private String workspaceId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "角色编码")
    private String roleCode;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "成员状态: 0-正常 1-禁用")
    private Integer status;

    @Schema(description = "加入时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime joinedAt;

    @Schema(description = "最后登录时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime lastLoginAt;

    @Schema(description = "是否为所有者")
    private Boolean isOwner;

    @JsonIgnore
    private String ownerId;

    public void setIsOwner() {
        this.isOwner = this.ownerId != null && this.ownerId.equals(this.userId);
    }
}
