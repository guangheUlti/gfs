import { Fragment } from 'react'
import { useTranslation } from 'react-i18next'
import { RiSettings3Fill, RiSettings3Line } from '@remixicon/react'
import { useAuth } from '@/contexts/auth-context'
import { useSettingsModal } from '@/contexts/settings-modal-context'
import { usePermission } from '@/hooks/use-permission'
import { useFeatureStore } from '@/store/feature'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarRail,
  SidebarSeparator,
  SidebarTrigger,
} from '@/components/ui/sidebar'
import { sidebarData } from './data/sidebar-data'
import { NavGroup } from './nav-group'
import { NavUser } from './nav-user'
import { StorageUsageBar } from './storage-usage-bar'

export function AppSidebar() {
  const { t } = useTranslation('layout')
  const { user: authUser } = useAuth()
  const { hasPermission } = usePermission()
  const { openSettings } = useSettingsModal()
  const featureToggles = useFeatureStore((s) => s.toggles)

  // 使用真实用户信息，如果未登录则使用占位符
  const user = authUser
    ? {
        name: authUser.nickname || authUser.username,
        avatar: authUser.avatar,
      }
    : { ...sidebarData.user, name: t('sidebar.userPlaceholder') }

  const navGroups = sidebarData.navGroups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          (!item.permission || hasPermission(item.permission)) &&
          (!item.featureKey || !!featureToggles[item.featureKey])
      ),
    }))
    // 系统分组末尾注入「设置」：全局弹窗而非路由，人人可用，不参与权限过滤
    .map((group) =>
      group.titleKey === 'sidebar.groups.system'
        ? {
            ...group,
            items: [
              ...group.items,
              {
                titleKey: 'sidebar.nav.settings',
                icon: { line: RiSettings3Line, fill: RiSettings3Fill },
                onClick: () => openSettings(),
              },
            ],
          }
        : group
    )
    .filter((group) => group.items.length > 0)

  return (
    <Sidebar variant='sidebar' collapsible='icon'>
      {/* 折叠/展开开关融入侧栏自身：展开时贴用户行右缘，折叠后隐藏（入口移至页脚最底部） */}
      <SidebarHeader className='relative'>
        <NavUser user={user} />
        <SidebarTrigger className='absolute end-2 top-1/2 -translate-y-1/2 text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-foreground group-data-[collapsible=icon]:hidden' />
      </SidebarHeader>
      <SidebarContent>
        {navGroups.map((group, index) => (
          <Fragment key={group.titleKey}>
            {index > 0 && (
              <SidebarSeparator className='mx-4 hidden group-data-[collapsible=icon]:block' />
            )}
            <NavGroup titleKey={group.titleKey} items={group.items} />
          </Fragment>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <StorageUsageBar />
        {/* 折叠后展开按钮固定在菜单栏最底部（折叠时存储用量条不渲染，页脚即空） */}
        <SidebarTrigger className='hidden self-center text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-foreground group-data-[collapsible=icon]:flex' />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
