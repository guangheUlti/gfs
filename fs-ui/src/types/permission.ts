/**
 * 权限编码：与后端 `UserPermissions` 保持一致。
 * 文件读写与分享所有登录用户都有，存储管理与日志查看仅系统管理员有。
 */
export const PermissionCode = {
  FILE_READ: 'file:read',
  FILE_WRITE: 'file:write',
  FILE_SHARE: 'file:share',
  STORAGE_MANAGE: 'storage:manage',
  LOG_READ: 'log:read',
} as const

export type PermissionCodeType =
  (typeof PermissionCode)[keyof typeof PermissionCode]
