import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
  RiAdminFill,
  RiAdminLine,
  RiArrowLeftRightFill,
  RiArrowLeftRightLine,
  RiComputerFill,
  RiComputerLine,
  RiEqualizer2Fill,
  RiEqualizer2Line,
  RiServerFill,
  RiServerLine,
  RiTShirtFill,
  RiTShirtLine,
  RiUserSettingsFill,
  RiUserSettingsLine,
} from '@remixicon/react'
import { NoPermission } from '@/components/no-permission'
import type { SidebarNavIconPair } from '@/components/layout/types'
import { SettingsProfile } from './profile'
import { SettingsAppearance } from './appearance'
import { SettingsTransfer } from './transfer'
import { SettingsUserManagement } from './user-management'
import { SettingsLoginManagement } from './login-management'
import { SettingsFeatureToggles } from './feature-toggles'
import { SettingsSystemManagement } from './system-management'
import { SidebarNav, type SettingsNavGroup } from './components/sidebar-nav'
import { useAuth } from '@/contexts/auth-context'

export type SettingsTab =
  | 'profile'
  | 'appearance'
  | 'transfer'
  | 'user-management'
  | 'login-management'
  | 'feature-toggles'
  | 'system-management'

interface NavItemConfig {
  title: string
  tab: SettingsTab
  icon: SidebarNavIconPair
  /** 仅系统管理员可见 */
  superAdminOnly?: boolean
}

function buildNavConfig(
  t: TFunction<'settings'>
): { label: string; items: NavItemConfig[] }[] {
  return [
    {
      label: t('nav.groupAccount'),
      items: [
        {
          title: t('nav.profile'),
          tab: 'profile',
          icon: { line: RiUserSettingsLine, fill: RiUserSettingsFill },
        },
        {
          title: t('nav.preference'),
          tab: 'appearance',
          icon: { line: RiTShirtLine, fill: RiTShirtFill },
        },
        {
          title: t('nav.transfer'),
          tab: 'transfer',
          icon: {
            line: RiArrowLeftRightLine,
            fill: RiArrowLeftRightFill,
          },
        },
      ],
    },
    {
      label: t('nav.groupSystem'),
      items: [
        {
          title: t('nav.userManagement'),
          tab: 'user-management',
          icon: { line: RiAdminLine, fill: RiAdminFill },
          superAdminOnly: true,
        },
        {
          title: t('nav.loginManagement'),
          tab: 'login-management',
          icon: { line: RiComputerLine, fill: RiComputerFill },
          superAdminOnly: true,
        },
        {
          title: t('nav.featureToggles'),
          tab: 'feature-toggles',
          icon: { line: RiEqualizer2Line, fill: RiEqualizer2Fill },
        },
        {
          title: t('nav.systemManagement'),
          tab: 'system-management',
          icon: { line: RiServerLine, fill: RiServerFill },
          superAdminOnly: true,
        },
      ],
    },
  ]
}

function toNavGroups(
  config: ReturnType<typeof buildNavConfig>,
  isSuperAdmin: boolean
): SettingsNavGroup[] {
  return config
    .map((group) => ({
      label: group.label,
      items: group.items
        .filter((item) => !item.superAdminOnly || isSuperAdmin)
        .map(({ title, tab, icon }) => ({
          title,
          tab,
          icon,
        })),
    }))
    .filter((g) => g.items.length > 0)
}

function SettingsPanel({ tab }: { tab: SettingsTab }) {
  const { user } = useAuth()

  switch (tab) {
    case 'profile':
      return <SettingsProfile />
    case 'appearance':
      return <SettingsAppearance />
    case 'transfer':
      return <SettingsTransfer />
    case 'user-management':
      return user?.isSuperAdmin ? (
        <SettingsUserManagement />
      ) : (
        <NoPermission />
      )
    case 'login-management':
      return user?.isSuperAdmin ? (
        <SettingsLoginManagement />
      ) : (
        <NoPermission />
      )
    case 'feature-toggles':
      return user?.isSuperAdmin ? (
        <SettingsFeatureToggles />
      ) : (
        <NoPermission />
      )
    case 'system-management':
      return user?.isSuperAdmin ? (
        <SettingsSystemManagement />
      ) : (
        <NoPermission />
      )
    default:
      return <SettingsProfile />
  }
}

/** 设置页：直接占用右侧主内容区（与文件/存储/服务等页面一致），而非弹层 */
export function SettingsPage() {
  const { t } = useTranslation('settings')
  const { user } = useAuth()
  const [tab, setTab] = useState<SettingsTab>('profile')

  const navGroups = useMemo(
    () => toNavGroups(buildNavConfig(t), !!user?.isSuperAdmin),
    [t, user?.isSuperAdmin]
  )

  return (
    <div className='flex h-full flex-col'>
      {/* 顶部标题区：与其他页面共用 inset-divider 页头，保证距顶部距离/标题字号/下划线一致 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        <div className='flex h-9 w-full min-w-0 items-center sm:w-auto sm:flex-1'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('dialog.title')}
          </h2>
        </div>
      </div>
      <div className='flex min-h-0 min-w-0 flex-1 flex-col md:flex-row'>
        <SidebarNav
          groups={navGroups}
          activeTab={tab}
          onSelectTab={setTab}
        />
        <div className='min-h-0 min-w-0 flex-1 overflow-y-auto bg-background px-6 pt-3 pb-10 sm:px-8 md:pt-4 md:pb-12 lg:px-16'>
          <SettingsPanel tab={tab} />
        </div>
      </div>
    </div>
  )
}