import { request } from './request'
import type { PendingUser } from '@/types/user'

/**
 * 系统级管理 API（跨工作空间的全局操作，仅系统管理员可用）
 * 工作空间级的成员管理和邀请已迁移到 workspaceApi
 */
export const adminApi = {
  /** 待审核用户列表（新注册需管理员审核） */
  listPendingUsers: () => {
    return request.get<PendingUser[]>('/apis/admin/users/pending')
  },

  /** 审核通过（待审核 -> 正常） */
  approveUser: (userId: string) => {
    return request.put(`/apis/admin/users/${userId}/approve`)
  },

  /** 拒绝注册申请（待审核 -> 已拒绝） */
  rejectUser: (userId: string) => {
    return request.put(`/apis/admin/users/${userId}/reject`)
  },
}
