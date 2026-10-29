import type { PermissionCodeType } from './permission'

/** 与 GET/PUT `/apis/user/info` 返回的 `data` 对象一致 */
export interface UserInfo {
  id: string
  username: string
  nickname: string
  avatar: string
  status: number
  createdAt: string
  updatedAt: string
  lastLoginAt: string
  /** 是否已设置登录密码 */
  isSetPassword?: boolean
  /** 是否系统管理员（用户名与系统配置一致） */
  isSuperAdmin?: boolean
  /** 用户级权限编码，由后端按是否系统管理员下发 */
  permissions?: PermissionCodeType[]
}

/** 待审核用户（新注册需管理员审核） */
export interface PendingUser {
  id: string
  username: string
  nickname: string
  avatar: string
  createdAt: string
}

/** 管理员账号列表项 */
export interface AdminUser {
  id: string
  username: string
  nickname: string
  avatar?: string
  /** 0 正常 / 1 禁用 / 2 待审核 / 3 已拒绝 */
  status: number
  /** 是否为系统管理员（唯一超级管理员） */
  superAdmin: boolean
  createdAt?: string
  lastLoginAt?: string
  /** 当前在线终端数 */
  onlineTerminals: number
  /** 是否被防暴力锁定 */
  locked: boolean
  /** 锁定剩余秒数 */
  lockRemainingSeconds?: number
}

/** 账号列表查询参数 */
export interface AdminUserQuery {
  keyword?: string
  status?: number
  page?: number
  pageSize?: number
}

/** 修改账号资料 */
export interface AdminUserUpdateParams {
  nickname: string
}

/** 账号残留数据概览 */
export interface AdminUserDataSummary {
  fileCount: number
  recycleCount: number
  favoriteCount: number
  shareCount: number
  collectionCount: number
  transferTaskCount: number
  ossKeyCount: number
  hasData: boolean
}

/** 被锁定账号（连续登录失败触发防暴力锁定，锁定数据在服务端 Redis） */
export interface LockedUser {
  username: string
  /** 账号存在时才有 */
  nickname?: string
  avatar?: string
  /** 剩余锁定秒数（多 IP 锁定取最大值） */
  remainingSeconds: number
  /** 被锁定的来源 IP 数量 */
  ipCount: number
}

/** 管理员手动创建用户 POST `/apis/admin/users` */
export interface AdminUserCreateParams {
  username: string
  password: string
  confirmPassword: string
  /** 可选，缺省回退用户名 */
  nickname?: string
}

/** 在线登录终端（一个 token 即一个终端） */
export interface OnlineTerminal {
  /** 同一账号内从 0 开始，用于定位要下线的会话 */
  index: number
  /** token 末 6 位，用于确认要踢的还是同一个会话，不可用于登录 */
  tokenTail: string
  deviceType?: string
  ip?: string
  browser?: string
  os?: string
  /** 登录时间（毫秒时间戳） */
  loginTime?: number
  /** 最后活跃时间（毫秒时间戳） */
  lastActiveTime?: number
  /** 是否为当前管理员正在使用的会话 */
  current?: boolean
}

/** 在线登录用户及其终端 */
export interface OnlineUser {
  loginId: string
  username?: string
  nickname?: string
  avatar?: string
  status?: number
  terminals: OnlineTerminal[]
}

export interface LoginRes {
  accessToken: string
}

/** 登录方式：用户名 + 密码 */
export type LoginType = 'password'

export interface LoginParams {
  loginType: LoginType
  account: string
  password: string
  isRemember?: boolean
}

export interface UserRegisterParams {
  username: string
  password: string
  confirmPassword: string
  nickname: string
  avatar?: string
}

export interface UpdateUserInfoParams {
  nickname?: string
  avatar?: string
}

export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

/** 首次设置密码 POST `/apis/user/password` */
export interface SetPasswordParams {
  newPassword: string
  confirmPassword: string
}
