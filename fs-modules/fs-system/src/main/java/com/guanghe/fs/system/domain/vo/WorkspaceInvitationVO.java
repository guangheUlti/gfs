package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import com.guanghe.fs.system.domain.SysWorkspaceInvitation;
import io.github.linpeilie.annotations.AutoMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作空间邀请 VO
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
@AutoMapper(target = SysWorkspaceInvitation.class)
@Schema(description = "工作空间邀请信息")
public class WorkspaceInvitationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "邀请ID")
    private String id;

    @Schema(description = "工作空间ID")
    private String workspaceId;

    @Schema(description = "邀请邮箱")
    private String email;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "邀请人ID")
    private String invitedBy;

    @Schema(description = "邀请人昵称")
    private String invitedByName;

    @Schema(description = "邀请状态: 0-待接受 1-已接受 2-已过期 3-已取消")
    private Integer status;

    @Schema(description = "邀请过期时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime expiresAt;

    @Schema(description = "接受时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime acceptedAt;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;
}
