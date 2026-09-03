package com.guanghe.fs.system.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.system.domain.SysWorkspaceMember;

import static com.guanghe.fs.system.domain.table.SysWorkspaceMemberTableDef.SYS_WORKSPACE_MEMBER;

/**
 * 工作空间成员 mapper 接口
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
public interface SysWorkspaceMemberMapper extends BaseMapper<SysWorkspaceMember> {

    /**
     * 根据工作空间ID和用户ID查询成员
     */
    default SysWorkspaceMember findByWorkspaceAndUser(String workspaceId, String userId) {
        return selectOneByQuery(QueryWrapper.create()
                .where(SYS_WORKSPACE_MEMBER.WORKSPACE_ID.eq(workspaceId))
                .and(SYS_WORKSPACE_MEMBER.USER_ID.eq(userId)));
    }
}
