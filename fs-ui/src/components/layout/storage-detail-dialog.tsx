import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  getStorageCapacity,
  getSystemInfo,
  STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
} from '@/api/home'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { formatCapacityBytes } from '@/utils/format'

/** 使用率越高越醒目：与侧栏存储条同一套告警色 */
function usageColor(percent: number): { bar: string; text: string } {
  if (percent >= 90) return { bar: 'bg-red-500', text: 'text-red-500' }
  if (percent >= 75) return { bar: 'bg-amber-500', text: 'text-amber-500' }
  return { bar: 'bg-primary', text: 'text-primary' }
}

function formatPercent(percent: number): string {
  if (!Number.isFinite(percent)) return '—'
  if (percent <= 0) return '0%'
  if (percent < 1) return '<1%'
  return `${percent.toFixed(1)}%`
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

  const { data: sysInfo, isLoading: sysLoading } = useQuery({
    queryKey: ['systemInfo'],
    queryFn: getSystemInfo,
    staleTime: 30_000,
    enabled: open,
  })

  const usedBytes = capacity?.usedBytes ?? 0
  const totalBytes = capacity?.totalBytes ?? null
  const capacityKnown = capacity?.capacityKnown === true && totalBytes !== null
  const percent =
    capacityKnown && totalBytes ? Math.min(100, (usedBytes / totalBytes) * 100) : 0
  const color = usageColor(percent)

  return (
    <div className='space-y-4'>
      {/* 顶部：横向使用率条 + 已使用 */}
      <div className='rounded-xl border bg-card p-5'>
        {capacityLoading ? (
          <Skeleton className='h-12 w-full rounded-full' />
        ) : (
          <div className='space-y-2.5'>
            <div className='flex items-baseline justify-between gap-3'>
              <span className='text-xs text-muted-foreground'>
                {t('storageDialog.usedPercent')}
              </span>
              <span
                className={cn(
                  'text-2xl font-semibold tabular-nums leading-none',
                  color.text
                )}
              >
                {capacityKnown ? formatPercent(percent) : '—'}
              </span>
            </div>
            <div className='h-2.5 w-full overflow-hidden rounded-full bg-muted'>
              <div
                className={cn(
                  'h-full rounded-full transition-all duration-500',
                  color.bar
                )}
                style={{ width: `${capacityKnown ? percent : 0}%` }}
              />
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

      {/* 下方四个关键指标：存储类型 / 已用空间 / 剩余空间 / 总容量 */}
      <div className='grid grid-cols-2 gap-3 xl:grid-cols-4'>
        {[
          { label: t('storageDialog.files'), value: sysInfo?.storageType ?? '—' },
          { label: t('storageDialog.used'), value: formatCapacityBytes(usedBytes) },
          {
            label: t('storageDialog.free'),
            value: capacityKnown ? formatCapacityBytes((totalBytes as number) - usedBytes) : '—',
          },
          {
            label: t('storageDialog.total'),
            value: capacityKnown ? formatCapacityBytes(totalBytes) : '—',
          },
        ].map((item) => (
          <div key={item.label} className='min-w-0 rounded-lg bg-muted/50 p-3'>
            <div className='truncate text-xs text-muted-foreground'>{item.label}</div>
            <div className='mt-1 truncate text-base font-semibold tabular-nums'>
              {capacityLoading || sysLoading ? (
                <Skeleton className='h-5 w-16' />
              ) : (
                item.value
              )}
            </div>
          </div>
        ))}
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
          <DialogDescription>{t('storageDialog.description')}</DialogDescription>
        </DialogHeader>
        <StorageDetailBody open={open} />
      </DialogContent>
    </Dialog>
  )
}
