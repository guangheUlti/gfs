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

/** 移动端顶部汉堡按钮：仅手机端显示，点击打开侧边栏抽屉；桌面端侧栏固定展开，无需入口 */
function MobileSidebarTrigger() {
  const { t } = useTranslation('layout')
  const { setOpenMobile } = useSidebar()
  return (
    <Button
      variant='ghost'
      size='icon'
      aria-label={t('sidebar.openMenu')}
      className='fixed start-3 top-3 z-50 size-9 rounded-full bg-background/80 text-foreground shadow-sm backdrop-blur md:hidden'
      onClick={() => setOpenMobile(true)}
    >
      <Menu className='size-5' />
    </Button>
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
        <MobileSidebarTrigger />
        <Main fixed>{children}</Main>
      </SidebarInset>
    </SidebarProvider>
  )
}
