import { Fragment } from 'react'
import { useTranslation } from 'react-i18next'
import { RiSettings3Fill, RiSettings3Line } from '@remixicon/react'
import { useAuth } from '@/contexts/auth-context'
import { useSettingsModal } from '@/contexts/settings-modal-context'
import { usePermission } from '@/hooks/use-permission'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarRail,
  SidebarSeparator,
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
        (item) => !item.permission || hasPermission(item.permission)
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
      <SidebarHeader>
        <NavUser user={user} />
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
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
