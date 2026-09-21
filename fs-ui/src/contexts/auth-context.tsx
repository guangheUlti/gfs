import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  ReactNode,
} from 'react'
import type { UserInfo } from '@/types/user'
import { mergeUserInfo } from '@/utils/merge-user-info'
import { getActiveStoragePlatforms } from '@/api/storage'
import {
  setToken as saveToken,
  clearToken as removeToken,
  getToken,
} from '@/utils/auth'

interface AuthContextType {
  isAuthenticated: boolean
  user: UserInfo | null
  token: string | null
  login: (
    token: string,
    userInfo: UserInfo,
    remember?: boolean
  ) => Promise<void>
  logout: () => void
  updateUser: (patch: Partial<UserInfo>) => void
  /** 加载登录后的会话上下文：已启用存储平台与用户传输配置 */
  loadSessionContext: () => Promise<void>
  isLoading: boolean
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

interface AuthProviderProps {
  children: ReactNode
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [user, setUser] = useState<UserInfo | null>(null)
  const [token, setToken] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const loadStoragePlatform = async () => {
    try {
      const activePlatforms = await getActiveStoragePlatforms()
      // 多存储：保留用户已选且仍可用的存储；未选择 = 内置本地存储（不携带请求头）
      let stored: { settingId?: string } | null = null
      try {
        stored = JSON.parse(
          localStorage.getItem('current-storage-platform') || 'null'
        )
      } catch {
        stored = null
      }
      if (stored?.settingId) {
        const stillActive = activePlatforms?.find(
          (p) => p.settingId === stored!.settingId
        )
        if (stillActive) {
          localStorage.setItem(
            'current-storage-platform',
            JSON.stringify({
              settingId: stillActive.settingId,
              platformName: stillActive.platformName,
            })
          )
        } else {
          localStorage.removeItem('current-storage-platform')
        }
      }
    } catch (error) {
      console.error('获取存储平台配置失败:', error)
    }
  }

  const loadSessionContext = useCallback(async () => {
    await Promise.all([
      loadStoragePlatform(),
      import('@/store/user')
        .then(({ useUserStore }) => useUserStore.getState().loadTransferSetting())
        .catch(() => {}),
      import('@/store/feature')
        .then(({ useFeatureStore }) => useFeatureStore.getState().fetchToggles())
        .catch(() => {}),
    ])
  }, [])

  useEffect(() => {
    const initAuth = async () => {
      try {
        const storedToken = getToken()

        if (storedToken) {
          const { useUserStore } = await import('@/store/user')
          const userStore = useUserStore.getState()

          let userInfo: UserInfo | null = null

          try {
            const { userApi } = await import('@/api/user')
            userInfo = await userApi.getUserInfo()
            userStore.setUserInfo(userInfo)
          } catch {
            if (userStore.id) {
              userInfo = {
                id: userStore.id,
                username: userStore.username,
                nickname: userStore.nickname,
                avatar: userStore.avatar,
                status: userStore.status,
                createdAt: userStore.createdAt,
                updatedAt: userStore.updatedAt,
                lastLoginAt: userStore.lastLoginAt,
                isSetPassword: userStore.isSetPassword,
              }
            }
          }

          if (userInfo) {
            setToken(storedToken)
            setUser(userInfo)
            setIsAuthenticated(true)
            await loadSessionContext()
          }
        }
      } catch (error) {
        console.error('初始化认证信息失败:', error)
        removeToken()
      } finally {
        setIsLoading(false)
      }
    }

    initAuth()
  }, [loadSessionContext])

  const login = useCallback(
    async (accessToken: string, userInfo: UserInfo, remember = false) => {
      try {
        saveToken(accessToken, remember)
        setToken(accessToken)
        setUser(userInfo)
        setIsAuthenticated(true)

        const { useUserStore } = await import('@/store/user')
        const userStore = useUserStore.getState()
        userStore.setUserInfo(userInfo)

        await loadSessionContext()
      } catch (error) {
        console.error('登录失败:', error)
        throw error
      }
    },
    [loadSessionContext]
  )

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
    setIsAuthenticated(false)

    removeToken()
    localStorage.removeItem('current-storage-platform')

    import('@/store/user').then(({ useUserStore }) => {
      useUserStore.getState().clearUserInfo()
    })
    import('@/store/feature').then(({ useFeatureStore }) => {
      useFeatureStore.getState().reset()
    })
    import('@/store/files-location').then(({ useFilesLocationStore }) => {
      useFilesLocationStore.getState().clear()
    })
  }, [])

  const updateUser = useCallback((patch: Partial<UserInfo>) => {
    setUser((prev) => {
      if (!prev) {
        return patch as UserInfo
      }
      const merged = mergeUserInfo(prev, patch)
      import('@/store/user').then(({ useUserStore }) => {
        useUserStore.getState().setUserInfo(merged)
      })
      return merged
    })
  }, [])

  const value = useMemo<AuthContextType>(
    () => ({
      isAuthenticated,
      user,
      token,
      login,
      logout,
      updateUser,
      loadSessionContext,
      isLoading,
    }),
    [
      isAuthenticated,
      user,
      token,
      login,
      logout,
      updateUser,
      loadSessionContext,
      isLoading,
    ]
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
