import { useTranslation } from 'react-i18next'
import { Menu } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  SidebarInset,
  SidebarProvider,
  useSidebar,
} from '@/components/ui/sidebar'
import { AppSidebar } from './app-sidebar'
import { Main } from './main'

/**
 * 移动端顶栏：仅手机显示，左侧汉堡按钮打开侧边栏抽屉，桌面端侧栏固定展开无需入口。
 *
 * 这里必须占位而不是用 fixed 浮层：各页页头统一是 px-3 pt-4，浮层落在 (11,11) 会正好盖住
 * 页面标题（根目录的「全部文件」面包屑也因此点不到）。改成占位顶栏后内容整体下移，不再重叠。
 */
function MobileTopBar() {
  const { t } = useTranslation('layout')
  const { setOpenMobile } = useSidebar()
  return (
    <div className='flex h-12 shrink-0 items-center gap-1 border-b bg-background px-1.5 md:hidden'>
      <Button
        variant='ghost'
        size='icon'
        aria-label={t('sidebar.openMenu')}
        className='size-9 rounded-lg'
        onClick={() => setOpenMobile(true)}
      >
        <Menu className='size-5' />
      </Button>
      <span className='truncate text-sm font-semibold tracking-tight'>
        {t('sidebar.appName')}
      </span>
    </div>
  )
}

interface AppLayoutProps {
  children: React.ReactNode
}

export function AppLayout({ children }: AppLayoutProps) {
  return (
    <SidebarProvider className='h-svh overflow-hidden'>
      <AppSidebar />
      <SidebarInset>
        <MobileTopBar />
        <Main fixed>{children}</Main>
      </SidebarInset>
    </SidebarProvider>
  )
}
