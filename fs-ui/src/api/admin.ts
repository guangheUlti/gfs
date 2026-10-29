import { request } from './request'
import type { PageRecord } from '@/types/page'
import type {
  AdminUser,
  AdminUserCreateParams,
  AdminUserDataSummary,
  AdminUserQuery,
  AdminUserUpdateParams,
  LockedUser,
  OnlineUser,
  PendingUser,
} from '@/types/user'

/**
 * 系统级管理 API（全局操作，仅系统管理员可用）
 */
export const adminApi = {
  /** 账号列表（分页，含在线终端与锁定状态） */
  listUsers: (params?: AdminUserQuery) => {
    return request.get<PageRecord<AdminUser>>('/apis/admin/users', { params })
  },

  /** 修改账号资料（用户名不可改，仅昵称） */
  updateUser: (userId: string, data: AdminUserUpdateParams) => {
    return request.put(`/apis/admin/users/${userId}`, data)
  },

  /** 启用/禁用账号（禁用同时强制下线） */
  setUserEnabled: (userId: string, enabled: boolean) => {
    return request.put(`/apis/admin/users/${userId}/status`, { enabled })
  },

  /** 重置账号密码（重置后该账号全部会话下线） */
  resetUserPassword: (userId: string, password: string) => {
    return request.put(`/apis/admin/users/${userId}/password`, { password })
  },

  /** 账号残留数据概览（删除前确认） */
  getUserDataSummary: (userId: string) => {
    return request.get<AdminUserDataSummary>(`/apis/admin/users/${userId}/data`)
  },

  /** 删除账号并彻底清除其全部数据 */
  deleteUser: (userId: string) => {
    return request.delete(`/apis/admin/users/${userId}`)
  },

  /** 手动创建用户（免审核，创建即可登录） */
  createUser: (data: AdminUserCreateParams) => {
    return request.post<void>('/apis/admin/users', data)
  },

  /** 待审核用户列表（新注册需管理员审核） */
  listPendingUsers: () => {
    return request.get<PendingUser[]>('/apis/admin/users/pending')
  },

  /** 审核通过（待审核 -> 正常） */
  approveUser: (userId: string) => {
    return request.put(`/apis/admin/users/${userId}/approve`)
  },

  /** 被锁定账号列表（连续登录失败触发防暴力锁定） */
  listLockedUsers: () => {
    return request.get<LockedUser[]>('/apis/admin/users/locked')
  },

  /** 解除账号锁定（清除该账号所有 IP 的锁定与失败计数） */
  unlockUser: (username: string) => {
    return request.put(
      `/apis/admin/users/locked/${encodeURIComponent(username)}/unlock`
    )
  },

  /** 拒绝注册申请（待审核 -> 已拒绝） */
  rejectUser: (userId: string) => {
    return request.put(`/apis/admin/users/${userId}/reject`)
  },

  /** 在线登录用户及其终端（登录管理） */
  listOnlineSessions: (keyword?: string) => {
    return request.get<OnlineUser[]>('/apis/admin/sessions', {
      params: keyword ? { keyword } : undefined,
    })
  },

  /** 强制某账号全部终端下线，返回被下线的会话数 */
  kickoutUser: (loginId: string) => {
    return request.delete<number>(`/apis/admin/sessions/${loginId}`)
  },

  /**
   * 强制指定终端下线
   * tokenTail 用于比对列表是否已变化（期间有新登录或有端退出会让序号错位），不匹配后端会拒绝执行
   */
  kickoutTerminal: (loginId: string, index: number, tokenTail?: string) => {
    return request.delete(`/apis/admin/sessions/${loginId}/terminals/${index}`, {
      params: tokenTail ? { tokenTail } : undefined,
    })
  },
}
