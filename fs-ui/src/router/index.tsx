import { useTranslation } from 'react-i18next'
import { useAuth } from '@/contexts/auth-context'
import { usePermission } from '@/hooks/use-permission'
import type { PermissionCodeType } from '@/types/permission'
import FileManagerPage from '@/pages/files'
import LoginPage from '@/pages/login'
import SharePage from '@/pages/share'
import StoragePage from '@/pages/storage'
import ServicesPage from '@/pages/services'
import TransferPage from '@/pages/transfer'
import { SettingsPage } from '@/pages/settings'
import {
  createBrowserRouter,
  type LoaderFunctionArgs,
  Navigate,
  Outlet,
  redirect,
  useLocation,
} from 'react-router-dom'
import { useFilesLocationStore } from '@/store/files-location'
import { AppLayout } from '@/components/layout/app-layout'
import { NoPermission } from '@/components/no-permission'

function ProtectedRoute({
  children,
  requiredPermission,
}: {
  children: React.ReactNode
  requiredPermission?: PermissionCodeType
}) {
  const { t } = useTranslation('common')
  const { isAuthenticated, isLoading } = useAuth()
  const { hasPermission } = usePermission()

  if (isLoading) {
    return (
      <div className='flex h-screen items-center justify-center'>
        {t('loading')}
      </div>
    )
  }

  if (!isAuthenticated) {
    return <Navigate to='/login' replace />
  }

  if (requiredPermission && !hasPermission(requiredPermission)) {
    return <NoPermission />
  }

  return <>{children}</>
}

/**
 * 裸 /files 重定向：用户从文件页切去其他页面（如传输页）再点侧边栏「文件」时，
 * 恢复上次浏览的目录位置（URL 是位置的唯一权威，在路由挂载前完成跳转，
 * 不会先白发一次根目录请求）。无记忆则按原样进入根目录。
 */
function filesLoader({ request }: LoaderFunctionArgs) {
  const url = new URL(request.url)
  // 仅拦截无查询参数的裸 /files（侧边栏/根重定向进入）；
  // 目录内导航始终携带参数，不做记忆劫持
  if (url.pathname !== '/files' || url.search) {
    return null
  }
  const { locationSearch, clearLocation } = useFilesLocationStore.getState()
  if (!locationSearch) {
    return null
  }
  clearLocation()
  return redirect(`/files?${locationSearch}`)
}

/** 登录后的应用外壳 */
function AppRoute() {
  return (
    <AppLayout>
      <Outlet />
    </AppLayout>
  )
}

/** 根路径与未匹配路径重定向：已登录 → /files（保留查询参数），未登录 → /login */
function RootRedirect() {
  const { t } = useTranslation('common')
  const { isAuthenticated, isLoading } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return (
      <div className='flex h-screen items-center justify-center'>
        {t('loading')}
      </div>
    )
  }

  if (!isAuthenticated) {
    return <Navigate to='/login' replace />
  }

  return <Navigate to={`/files${location.search}`} replace />
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/s/:shareToken',
    element: <SharePage />,
  },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppRoute />
      </ProtectedRoute>
    ),
    children: [
      {
        index: true,
        element: <RootRedirect />,
      },
      {
        path: 'files',
        element: <FileManagerPage />,
        loader: filesLoader,
      },
      {
        path: 'storage',
        element: (
          <ProtectedRoute requiredPermission='storage:manage'>
            <StoragePage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'services',
        element: (
          <ProtectedRoute requiredPermission='service:manage'>
            <ServicesPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'transfer',
        element: <TransferPage />,
      },
      {
        path: 'settings',
        element: <SettingsPage />,
      },
    ],
  },
  {
    path: '*',
    element: <RootRedirect />,
  },
])
