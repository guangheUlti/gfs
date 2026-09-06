import { useCallback, useMemo } from 'react'
import { useUserStore } from '@/store/user'
import type { PermissionCodeType } from '@/types/permission'

/**
 * 用户级权限：权限编码直接取自 `/apis/user/info` 下发的 `permissions`，
 * 系统已移除工作空间与角色体系，不再有「当前角色」这一中间层。
 */
export function usePermission() {
  const permissions = useUserStore((s) => s.permissions)
  const isSuperAdmin = useUserStore((s) => s.isSuperAdmin)

  const permissionSet = useMemo(
    () => new Set<PermissionCodeType>(permissions ?? []),
    [permissions]
  )

  const hasPermission = useCallback(
    (code: PermissionCodeType) => permissionSet.has(code),
    [permissionSet]
  )

  const hasAnyPermission = useCallback(
    (...codes: PermissionCodeType[]) => codes.some((c) => permissionSet.has(c)),
    [permissionSet]
  )

  const hasAllPermissions = useCallback(
    (...codes: PermissionCodeType[]) =>
      codes.every((c) => permissionSet.has(c)),
    [permissionSet]
  )

  return {
    hasPermission,
    hasAnyPermission,
    hasAllPermissions,
    isAdmin: !!isSuperAdmin,
  }
}
