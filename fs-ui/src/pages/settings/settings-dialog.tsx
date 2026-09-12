import { useMemo } from 'react'
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
  RiTShirtFill,
  RiTShirtLine,
  RiUserSettingsFill,
  RiUserSettingsLine,
} from '@remixicon/react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { NoPermission } from '@/components/no-permission'
import {
  useSettingsModal,
  type SettingsTab,
} from '@/contexts/settings-modal-context'
import type { SidebarNavIconPair } from '@/components/layout/types'
import { SettingsProfile } from './profile'
import { SettingsAppearance } from './appearance'
import { SettingsTransfer } from './transfer'
import { SettingsUserApproval } from './user-approval'
import { SettingsLoginManagement } from './login-management'
import { SettingsFeatureToggles } from './feature-toggles'
import { SidebarNav, type SettingsNavGroup } from './components/sidebar-nav'
import { useAuth } from '@/contexts/auth-context'

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
          title: t('nav.userApproval'),
          tab: 'user-approval',
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
    case 'user-approval':
      return user?.isSuperAdmin ? (
        <SettingsUserApproval />
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
    default:
      return <SettingsProfile />
  }
}

export function SettingsDialog() {
  const { t } = useTranslation('settings')
  const { open, setOpen, tab, setTab } = useSettingsModal()
  const { user } = useAuth()

  const navGroups = useMemo(
    () => toNavGroups(buildNavConfig(t), !!user?.isSuperAdmin),
    [t, user?.isSuperAdmin]
  )

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        showCloseButton={false}
        className='flex h-[min(92vh,960px)] max-h-[92vh] w-[min(1240px,calc(100%-32px))] max-w-none translate-x-[-50%] translate-y-[-50%] flex-col gap-0 overflow-hidden border bg-background p-0 shadow-xl sm:max-w-none sm:rounded-xl'
      >
        <div className='flex h-12 shrink-0 items-center justify-between border-b px-6 md:px-8'>
          <DialogTitle className='text-base font-semibold tracking-tight'>
            {t('dialog.title')}
          </DialogTitle>
          <Button
            type='button'
            variant='ghost'
            size='icon'
            className='size-8 shrink-0'
            onClick={() => setOpen(false)}
            aria-label={t('dialog.closeAria')}
          >
            <X className='size-4' />
          </Button>
        </div>

        <div className='flex min-h-0 min-w-0 flex-1 flex-col md:flex-row'>
          <SidebarNav
            groups={navGroups}
            activeTab={tab}
            onSelectTab={setTab}
          />
          <div className='min-h-0 min-w-0 flex-1 overflow-y-auto bg-background px-6 py-6 sm:px-8 md:px-12 md:py-8 lg:px-16'>
            <SettingsPanel tab={tab} />
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
