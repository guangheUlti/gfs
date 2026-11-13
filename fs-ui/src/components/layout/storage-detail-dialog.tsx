import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  getStorageCapacity,
  STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
} from '@/api/home'
import { cn } from '@/lib/utils'
import { formatCapacityBytes } from '@/utils/format'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'

/** 使用率越高越醒目：与侧栏存储条同一套告警色 */
function usageColor(percent: number): {
  bar: string
  text: string
} {
  if (percent >= 90) {
    return {
      bar: 'bg-red-500',
      text: 'text-red-500',
    }
  }
  if (percent >= 75) {
    return {
      bar: 'bg-amber-500',
      text: 'text-amber-500',
    }
  }
  return {
    bar: 'bg-primary',
    text: 'text-primary',
  }
}

function StorageDetailBody({ open }: { open: boolean }) {
  const { t } = useTranslation('layout')

  const { data: capacity, isLoading: capacityLoading } = useQuery({
    queryKey: ['storageCapacity'],
    queryFn: getStorageCapacity,
    staleTime: 60_000,
    refetchInterval: STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
    enabled: open,
  })

  const usedBytes = capacity?.usedBytes ?? 0
  const totalBytes = capacity?.totalBytes ?? null
  const capacityKnown = capacity?.capacityKnown === true && totalBytes !== null
  const percent =
    capacityKnown && totalBytes
      ? Math.min(100, (usedBytes / totalBytes) * 100)
      : 0
  const color = usageColor(percent)
  const freeBytes = capacityKnown
    ? Math.max(0, (totalBytes as number) - usedBytes)
    : null
  const usedShare =
    capacityKnown && usedBytes > 0
      ? Math.min(100, Math.max(1, Math.round(percent)))
      : 0
  const freeShare = capacityKnown ? 100 - usedShare : 0

  return (
    <div className='space-y-5'>
      {/* 占用概况：铺满卡片宽度的矩形进度条，占用数据放在矩形条下方 */}
      <div className='rounded-xl border bg-card px-6 py-6'>
        {capacityLoading ? (
          <Skeleton className='h-14 w-full rounded-lg' />
        ) : (
          <div className='space-y-4'>
            <div
              className='h-14 w-full overflow-hidden rounded-lg bg-muted'
              role='progressbar'
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={capacityKnown ? Math.round(percent) : undefined}
            >
              <div
                className={cn('h-full transition-all duration-500', color.bar)}
                style={{ width: `${capacityKnown ? percent : 0}%` }}
              />
            </div>

            {/* 矩形条下方：已用/总量摘要 + 构成占比 */}
            <div className='space-y-2.5'>
              <div className='flex flex-wrap items-baseline gap-x-6 gap-y-1'>
                <p className='text-lg font-semibold tabular-nums'>
                  {capacityKnown && totalBytes !== null
                    ? t('sidebar.storage.usedOfTotal', {
                        used: formatCapacityBytes(usedBytes),
                        total: formatCapacityBytes(totalBytes),
                      })
                    : t('sidebar.storage.usedOnly', {
                        used: formatCapacityBytes(usedBytes),
                      })}
                </p>
                <p className='text-xs text-muted-foreground tabular-nums'>
                  {freeBytes === null
                    ? '—'
                    : t('storageDialog.diskFree', {
                        free: formatCapacityBytes(freeBytes),
                      })}
                </p>
              </div>

              <div className='flex flex-wrap items-center gap-x-5 gap-y-2'>
                {[
                  {
                    color: color.bar,
                    label: t('storageDialog.used'),
                    share: usedShare,
                    shareColor: color.text,
                  },
                  {
                    color: 'bg-muted-foreground/40',
                    label: t('storageDialog.free'),
                    share: freeShare,
                    shareColor: 'text-foreground',
                  },
                ].map((item) => (
                  <div key={item.label} className='flex items-center gap-2'>
                    <span
                      className={cn(
                        'size-2.5 shrink-0 rounded-full',
                        item.color
                      )}
                    />
                    <span className='text-xs text-muted-foreground'>
                      {item.label}
                    </span>
                    <span
                      className={cn(
                        'text-sm font-semibold tabular-nums',
                        item.shareColor
                      )}
                    >
                      {capacityKnown ? `${item.share}%` : '—'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {!capacityKnown && !capacityLoading && (
        <div className='flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400'>
          <Info className='mt-0.5 size-3.5 shrink-0' />
          <span>{t('storageDialog.capacityUnknown')}</span>
        </div>
      )}

      {/* 统计口径说明：把「已用/总容量」的计算方式写清楚，省得看到 1% 以为统计错了 */}
      <div className='flex items-start gap-2 rounded-lg bg-muted/40 px-3 py-2.5 text-xs leading-relaxed text-muted-foreground'>
        <Info className='mt-0.5 size-3.5 shrink-0' />
        <span>{t('storageDialog.capacityNote')}</span>
      </div>
    </div>
  )
}

export function StorageDetailDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation('layout')

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[85vh] overflow-y-auto sm:max-w-3xl'>
        <DialogHeader>
          <DialogTitle>{t('storageDialog.title')}</DialogTitle>
          <DialogDescription>
            {t('storageDialog.description')}
          </DialogDescription>
        </DialogHeader>
        <StorageDetailBody open={open} />
      </DialogContent>
    </Dialog>
  )
}
