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
