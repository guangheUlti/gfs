import { TransferSetting } from '@/types/transfer-setting'
import { UserInfo } from '@/types/user'
import type { PermissionCodeType } from '@/types/permission'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { userApi } from '@/api/user'

interface UserState {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  status: number
  createdAt: string
  updatedAt: string
  lastLoginAt: string
  isSetPassword?: boolean
  /** 是否系统管理员，决定能否看到存储与日志入口 */
  isSuperAdmin?: boolean
  /** 用户级权限编码，由 `/apis/user/info` 下发 */
  permissions?: PermissionCodeType[]
  transferSetting?: TransferSetting
  setUserInfo: (userInfo: UserInfo) => void
  setTransferSetting: (setting: TransferSetting) => void
  loadTransferSetting: () => Promise<void>
  clearUserInfo: () => void
}

export const useUserStore = create<UserState>()(
  persist(
    (set) => ({
      id: '',
      username: '',
      nickname: '',
      email: '',
      avatar: '',
      status: 0,
      createdAt: '',
      updatedAt: '',
      lastLoginAt: '',
      isSetPassword: undefined,
      isSuperAdmin: undefined,
      permissions: undefined,
      transferSetting: undefined,
      setUserInfo: (userInfo) =>
        set({
          id: userInfo.id,
          username: userInfo.username,
          nickname: userInfo.nickname,
          email: userInfo.email,
          avatar: userInfo.avatar,
          status: userInfo.status,
          createdAt: userInfo.createdAt,
          updatedAt: userInfo.updatedAt,
          lastLoginAt: userInfo.lastLoginAt,
          isSetPassword: userInfo.isSetPassword,
          isSuperAdmin: userInfo.isSuperAdmin,
          permissions: userInfo.permissions,
        }),
      setTransferSetting: (setting) => set({ transferSetting: setting }),
      loadTransferSetting: async () => {
        try {
          const setting = await userApi.getTransferSetting()
          set({ transferSetting: setting })
        } catch (error) {
          set({
            transferSetting: {
              userId: '',
              downloadLocation: '',
              isDefaultDownloadLocation: 1,
              downloadSpeedLimit: 0,
              concurrentUploadQuantity: 3,
              concurrentDownloadQuantity: 3,
              chunkSize: 5 * 1024 * 1024,
            },
          })
        }
      },
      clearUserInfo: () =>
        set({
          id: '',
          username: '',
          nickname: '',
          email: '',
          avatar: '',
          status: 0,
          createdAt: '',
          updatedAt: '',
          lastLoginAt: '',
          isSetPassword: undefined,
          isSuperAdmin: undefined,
          permissions: undefined,
          transferSetting: undefined,
        }),
    }),
    {
      name: 'user-storage',
    }
  )
)
