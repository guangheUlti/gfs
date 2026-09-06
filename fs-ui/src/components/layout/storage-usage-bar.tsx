import { useQuery } from '@tanstack/react-query'
import { HardDrive } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  getStorageCapacity,
  STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
} from '@/api/home'
import { cn } from '@/lib/utils'
import { formatCapacityBytes } from '@/utils/format'
import { useSidebar } from '@/components/ui/sidebar'
import { Skeleton } from '@/components/ui/skeleton'

/** 使用率文案：0 与非 0 极小值分开处理，避免出现「0.0%」掩盖真实占用 */
function formatPercent(percent: number): string {
  if (percent <= 0) return '0%'
  if (percent < 0.1) return '<0.1%'
  if (percent >= 100) return '100%'
  return `${percent.toFixed(1)}%`
}

/** 使用率越高越醒目，接近写满时直接告警色 */
function barColorClass(percent: number): string {
  if (percent >= 90) return 'bg-red-500'
  if (percent >= 75) return 'bg-amber-500'
  return 'bg-primary'
}

/**
 * 侧边栏底部存储概览：使用率百分比 + 进度条 + 已用/总量。
 *
 * 口径来自服务端：总量 = 存储底座剩余可写空间 + 本系统已存文件占用，已用 = 本系统已存文件占用。
 * 对象存储给不出磁盘容量时 `capacityKnown` 为 false，此时只展示已用量，不画没有依据的百分比。
 */
export function StorageUsageBar({ className }: { className?: string }) {
  const { t } = useTranslation('layout')
  const { state } = useSidebar()

  const { data, isLoading } = useQuery({
    queryKey: ['storageCapacity'],
    queryFn: getStorageCapacity,
    staleTime: 60_000,
    refetchInterval: STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
  })

  // 折叠为图标模式时无展示空间
  if (state === 'collapsed') return null

  const usedBytes = data?.usedBytes ?? 0
  const totalBytes = data?.totalBytes ?? null
  const capacityKnown =
    data?.capacityKnown === true && totalBytes !== null && totalBytes > 0
  const percent = capacityKnown
    ? Math.min(100, Math.max(0, (usedBytes / (totalBytes as number)) * 100))
    : 0

  const usageText = capacityKnown
    ? t('sidebar.storage.usedOfTotal', {
        used: formatCapacityBytes(usedBytes),
        total: formatCapacityBytes(totalBytes),
      })
    : t('sidebar.storage.usedOnly', {
        used: formatCapacityBytes(usedBytes),
      })

  return (
    <div
      className={cn(
        'mx-2 flex flex-col gap-1.5 rounded-lg px-2 py-2 transition-colors hover:bg-sidebar-accent',
        className
      )}
    >
      <div className='flex items-center gap-2'>
        <HardDrive
          className='size-4 shrink-0 text-muted-foreground'
          strokeWidth={1.75}
        />
        <span className='truncate text-xs font-medium text-muted-foreground'>
          {t('sidebar.storage.title')}
        </span>
        {capacityKnown && !isLoading && (
          <span className='ms-auto shrink-0 text-xs font-semibold tabular-nums'>
            {formatPercent(percent)}
          </span>
        )}
      </div>

      {isLoading ? (
        <Skeleton className='h-1.5 w-full rounded-full' />
      ) : (
        capacityKnown && (
          <div
            role='progressbar'
            aria-label={t('sidebar.storage.title')}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(percent)}
            className='h-1.5 w-full overflow-hidden rounded-full bg-sidebar-accent'
          >
            <div
              className={cn(
                'h-full rounded-full transition-all',
                barColorClass(percent)
              )}
              style={{ width: `${percent}%` }}
            />
          </div>
        )
      )}

      {isLoading ? (
        <Skeleton className='h-3 w-28' />
      ) : (
        <span
          className='truncate text-xs text-muted-foreground tabular-nums'
          title={usageText}
        >
          {usageText}
        </span>
      )}
    </div>
  )
}
