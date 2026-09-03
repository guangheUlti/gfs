import type { PermissionCodeType } from './permission'

/** 用户在某个工作空间内的角色与权限 */
export interface UserRolePermissions {
  roleCode: string
  roleName: string
  permissions: PermissionCodeType[]
}

/** 与 GET/PUT `/apis/user/info` 返回的 `data` 对象一致（全局信息，不含权限） */
export interface UserInfo {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  status: number
  createdAt: string
  updatedAt: string
  lastLoginAt: string
  /** 是否已设置登录密码 */
  isSetPassword?: boolean
  /** 是否系统管理员（用户名与系统配置一致） */
  isSuperAdmin?: boolean
}

/** 待审核用户（新注册需管理员审核） */
export interface PendingUser {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  createdAt: string
}

export interface LoginRes {
  accessToken: string
}

/** 登录方式：账号/邮箱 + 密码 */
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
  email: string
  nickname: string
  avatar?: string
  inviteToken?: string
}

export interface UpdateUserInfoParams {
  nickname?: string
  email?: string
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
