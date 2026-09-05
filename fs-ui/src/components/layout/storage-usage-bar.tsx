import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { HardDrive } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'
import { useSidebar } from '@/components/ui/sidebar'
import {
  getHomeInfo,
  HOME_INFO_REFETCH_INTERVAL_MS,
  type HomeUsedBytesUnit,
} from '@/api/home'
import { formatHomeStorageDisplay } from '@/utils/format'
import { cn } from '@/lib/utils'

const SIDEBAR_STORAGE_UNIT: HomeUsedBytesUnit = 3

/** 侧边栏底部存储概览：仅展示当前工作空间+当前存储平台的已用空间（无总额度，不造百分比） */
export function StorageUsageBar({ className }: { className?: string }) {
  const { t } = useTranslation('layout')
  const { state } = useSidebar()

  const { data, isLoading } = useQuery({
    queryKey: ['homeInfo', 'sidebar-storage', SIDEBAR_STORAGE_UNIT],
    queryFn: () => getHomeInfo({ unit: SIDEBAR_STORAGE_UNIT }),
    staleTime: 30_000,
    refetchInterval: HOME_INFO_REFETCH_INTERVAL_MS,
  })

  // 折叠为图标模式时无展示空间
  if (state === 'collapsed') return null

  const usedStorage = data?.usedStorage
  const unitLabel = data?.unit ?? ''
  const display =
    usedStorage === undefined
      ? '—'
      : formatHomeStorageDisplay(usedStorage, unitLabel, SIDEBAR_STORAGE_UNIT)

  return (
    <div
      className={cn(
        'mx-2 flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors hover:bg-sidebar-accent',
        className
      )}
    >
      <div className='flex size-8 shrink-0 items-center justify-center rounded-md bg-sidebar-accent text-sidebar-primary'>
        <HardDrive className='size-4' strokeWidth={1.75} />
      </div>
      <div className='flex min-w-0 flex-1 flex-col gap-0.5'>
        <span className='truncate text-xs font-medium text-muted-foreground'>
          {t('sidebar.storageUsed')}
        </span>
        {isLoading ? (
          <Skeleton className='h-4 w-16' />
        ) : (
          <span className='truncate text-sm font-semibold tabular-nums' title={display}>
            {display}
          </span>
        )}
      </div>
    </div>
  )
}
