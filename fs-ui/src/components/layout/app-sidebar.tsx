import { useTranslation } from 'react-i18next'
import { RiSettings3Fill, RiSettings3Line } from '@remixicon/react'
import { useAuth } from '@/contexts/auth-context'
import { usePermission } from '@/hooks/use-permission'
import { useFeatureStore } from '@/store/feature'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
} from '@/components/ui/sidebar'
import { sidebarData } from './data/sidebar-data'
import { NavGroup } from './nav-group'
import { NavUser } from './nav-user'
import { StorageUsageBar } from './storage-usage-bar'

export function AppSidebar() {
  const { t } = useTranslation('layout')
  const { user: authUser } = useAuth()
  const { hasPermission } = usePermission()
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
    // 系统分组末尾注入「设置」：独立路由页面，人人可用，不参与权限过滤
    .map((group) =>
      group.titleKey === 'sidebar.groups.system'
        ? {
            ...group,
            items: [
              ...group.items,
              {
                titleKey: 'sidebar.nav.settings',
                url: '/settings',
                icon: { line: RiSettings3Line, fill: RiSettings3Fill },
              },
            ],
          }
        : group
    )
    .filter((group) => group.items.length > 0)

  return (
    <Sidebar variant='sidebar' collapsible='none'>
      {/* 桌面端侧边栏固定展开，不再支持折叠；移动端由汉堡按钮打开抽屉 */}
      <SidebarHeader>
        <NavUser user={user} />
      </SidebarHeader>
      <SidebarContent>
        {navGroups.map((group) => (
          <NavGroup
            key={group.titleKey}
            titleKey={group.titleKey}
            items={group.items}
          />
        ))}
      </SidebarContent>
      <SidebarFooter>
        <StorageUsageBar />
      </SidebarFooter>
    </Sidebar>
  )
}
