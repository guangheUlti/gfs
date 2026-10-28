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
  ring: string
  bar: string
  text: string
} {
  if (percent >= 90) {
    return {
      ring: 'stroke-red-500',
      bar: 'bg-red-500',
      text: 'text-red-500',
    }
  }
  if (percent >= 75) {
    return {
      ring: 'stroke-amber-500',
      bar: 'bg-amber-500',
      text: 'text-amber-500',
    }
  }
  return {
    ring: 'stroke-primary',
    bar: 'bg-primary',
    text: 'text-primary',
  }
}

/**
 * 使用率文案：只给百分比数字本身。
 * 不用 `<1%` / `>99%` 这类近似符号——真看不出来时给了也没有信息量，
 * 有占用就至少显示 1%（四舍五入为 0 会让「已用几百 MB」看起来像空盘）。
 */
function formatPercent(percent: number, usedBytes: number): string {
  if (!Number.isFinite(percent)) return '—'
  if (usedBytes <= 0) return '0%'
  return `${Math.min(100, Math.max(1, Math.round(percent)))}%`
}

/** 环形使用率（SVG 描边，12 点方向起画，避免 conic-gradient 的锯齿） */
function UsageRing({
  percent,
  color,
  label,
  valueText,
}: {
  percent: number
  color: string
  label: string
  valueText: string
}) {
  const radius = 42
  const circumference = 2 * Math.PI * radius
  const dash = (Math.min(100, Math.max(0, percent)) / 100) * circumference

  return (
    <div className='relative size-36 shrink-0'>
      <svg viewBox='0 0 100 100' className='size-36 -rotate-90'>
        <circle
          cx='50'
          cy='50'
          r={radius}
          fill='none'
          strokeWidth='11'
          className='stroke-muted'
        />
        <circle
          cx='50'
          cy='50'
          r={radius}
          fill='none'
          strokeWidth='11'
          strokeLinecap='round'
          strokeDasharray={`${dash} ${circumference}`}
          className={cn('transition-all duration-500', color)}
        />
      </svg>
      <div className='absolute inset-0 flex flex-col items-center justify-center gap-0.5'>
        <span className='text-2xl font-semibold tabular-nums'>{valueText}</span>
        <span className='mt-0.5 text-[11px] text-muted-foreground'>
          {label}
        </span>
      </div>
    </div>
  )
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
  const percentText = capacityKnown ? formatPercent(percent, usedBytes) : '—'
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
      {/* 顶部：环形使用率 + 已用/总量摘要 + 线性进度 */}
      <div className='rounded-xl border bg-card px-6 py-8'>
        {capacityLoading ? (
          <Skeleton className='h-36 w-full rounded-xl' />
        ) : (
          <div className='space-y-6'>
            <div className='flex flex-col items-center gap-6 sm:flex-row sm:items-center sm:gap-8'>
              <UsageRing
                percent={percent}
                color={color.ring}
                label={t('storageDialog.usedPercent')}
                valueText={percentText}
              />
              <div className='min-w-0 flex-1 space-y-2.5 text-center sm:text-start'>
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
            </div>

            {/* 进度条 + 构成占比：只给比例，字节数在上方摘要里 */}
            <div className='flex items-center gap-5'>
              <div className='h-2 flex-1 overflow-hidden rounded-full bg-muted'>
                <div
                  className={cn(
                    'h-full rounded-full transition-all duration-500',
                    color.bar
                  )}
                  style={{ width: `${capacityKnown ? percent : 0}%` }}
                />
              </div>
              <div className='hidden shrink-0 items-center gap-5 sm:flex'>
                {[
                  {
                    color: color.bar,
                    label: t('storageDialog.used'),
                    share: usedShare,
                  },
                  {
                    color: 'bg-muted-foreground/40',
                    label: t('storageDialog.free'),
                    share: freeShare,
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
                    <span className='text-sm font-semibold tabular-nums'>
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
