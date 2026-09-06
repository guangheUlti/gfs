import { request } from './request'
import type { OnlineUser, PendingUser } from '@/types/user'

/**
 * 系统级管理 API（全局操作，仅系统管理员可用）
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
