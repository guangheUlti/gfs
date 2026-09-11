import { useTranslation } from 'react-i18next'
import { useAuth } from '@/contexts/auth-context'
import { usePermission } from '@/hooks/use-permission'
import type { PermissionCodeType } from '@/types/permission'
import FileManagerPage from '@/pages/files'
import LoginPage from '@/pages/login'
import SharePage from '@/pages/share'
import StoragePage from '@/pages/storage'
import SftpServicePage from '@/pages/services/sftp'
import WebDavServicePage from '@/pages/services/webdav'
import TransferPage from '@/pages/transfer'
import {
  createBrowserRouter,
  Navigate,
  Outlet,
  useLocation,
} from 'react-router-dom'
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
        path: 'services/webdav',
        element: (
          <ProtectedRoute requiredPermission='service:manage'>
            <WebDavServicePage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'services/sftp',
        element: (
          <ProtectedRoute requiredPermission='service:manage'>
            <SftpServicePage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'transfer',
        element: <TransferPage />,
      },
    ],
  },
  {
    path: '*',
    element: <RootRedirect />,
  },
])
