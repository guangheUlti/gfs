import { useQuery } from '@tanstack/react-query'
import {
  Cpu,
  Clock3,
  FolderTree,
  HardDrive,
  Info,
  MemoryStick,
  Server,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  getStorageCapacity,
  getSystemInfo,
  STORAGE_CAPACITY_REFETCH_INTERVAL_MS,
  type DiskPartition,
} from '@/api/home'
import { AnimatedCircularProgressBar } from '@/components/ui/animated-circular-progress-bar'
import { Badge } from '@/components/ui/badge'
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
function usageColor(percent: number): { ring: string; bar: string; text: string } {
  if (percent >= 90)
    return { ring: 'bg-red-500', bar: 'bg-red-500', text: 'text-red-500' }
  if (percent >= 75)
    return { ring: 'bg-amber-500', bar: 'bg-amber-500', text: 'text-amber-500' }
  return { ring: 'bg-primary', bar: 'bg-primary', text: 'text-primary' }
}

function formatPercent(percent: number): string {
  if (!Number.isFinite(percent)) return '—'
  if (percent <= 0) return '0%'
  if (percent < 1) return '<1%'
  return `${percent.toFixed(1)}%`
}

function Row({
  icon: Icon,
  label,
  value,
  mono = false,
}: {
  icon: React.ComponentType<{ className?: string }>
  label: string
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div className='flex items-center gap-2 text-sm'>
      <Icon className='size-4 shrink-0 text-muted-foreground' strokeWidth={1.75} />
      <span className='shrink-0 text-muted-foreground'>{label}</span>
      <span
        className={cn(
          'ms-auto min-w-0 truncate text-right font-medium',
          mono && 'font-mono text-xs'
        )}
        title={typeof value === 'string' ? value : undefined}
      >
        {value}
      </span>
    </div>
  )
}

function Panel({
  title,
  icon: Icon,
  children,
  className,
}: {
  title: string
  icon: React.ComponentType<{ className?: string }>
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('rounded-xl border bg-card', className)}>
      <div className='flex items-center gap-2 border-b px-4 py-2.5'>
        <Icon className='size-4 text-muted-foreground' strokeWidth={1.75} />
        <span className='text-sm font-medium'>{title}</span>
      </div>
      <div className='space-y-2.5 px-4 py-3'>{children}</div>
    </div>
  )
}

function MiniUsageBar({
  usedBytes,
  totalBytes,
}: {
  usedBytes: number
  totalBytes: number
}) {
  const percent = totalBytes > 0 ? Math.min(100, (usedBytes / totalBytes) * 100) : 0
  const color = usageColor(percent)
  return (
    <div className='space-y-1'>
      <div className='h-1.5 w-full overflow-hidden rounded-full bg-muted'>
        <div
          className={cn('h-full rounded-full transition-all', color.bar)}
          style={{ width: `${percent}%` }}
        />
      </div>
      <div className='flex justify-between text-xs text-muted-foreground tabular-nums'>
        <span>{formatCapacityBytes(usedBytes)}</span>
        <span>{formatPercent(percent)}</span>
      </div>
    </div>
  )
}

function DiskCard({
  disk,
  highlight,
  t,
}: {
  disk: DiskPartition
  highlight?: boolean
  t: (key: string) => string
}) {
  return (
    <div
      className={cn(
        'rounded-lg border p-3',
        highlight && 'border-primary/40 bg-primary/5'
      )}
    >
      <div className='mb-1.5 flex items-center gap-1.5'>
        <HardDrive className='size-3.5 text-muted-foreground' strokeWidth={1.75} />
        <span
          className='min-w-0 truncate font-mono text-xs font-medium'
          title={disk.mountPoint}
        >
          {disk.mountPoint}
        </span>
        {highlight && (
          <Badge variant='secondary' className='ms-auto shrink-0 text-[10px]'>
            {t('storageDialog.currentStorage')}
          </Badge>
        )}
      </div>
      <MiniUsageBar usedBytes={disk.usedBytes} totalBytes={disk.totalBytes} />
      <div className='mt-1 text-xs text-muted-foreground tabular-nums'>
        {t('storageDialog.diskTotal', { total: formatCapacityBytes(disk.totalBytes) })}
        {' · '}
        {t('storageDialog.diskFree', { free: formatCapacityBytes(disk.freeBytes) })}
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

  const storagePath = sysInfo?.storagePath ?? null

  /** 高亮与当前存储路径匹配的磁盘分区 */
  const isCurrentDisk = (disk: DiskPartition) =>
    !!storagePath && disk.mountPoint != null && storagePath.startsWith(disk.mountPoint)

  return (
    <div className='space-y-4'>
      {/* 顶部：环形使用率 + 关键数字 */}
      <div className='flex flex-col items-center gap-5 rounded-xl border bg-card p-5 sm:flex-row'>
        {capacityLoading ? (
          <Skeleton className='size-36 rounded-full' />
        ) : (
          <div className='relative shrink-0'>
            <AnimatedCircularProgressBar
              value={Math.round(percent)}
              gaugePrimaryColor={
                percent >= 90 ? '#ef4444' : percent >= 75 ? '#f59e0b' : 'var(--primary)'
              }
              gaugeSecondaryColor='var(--muted)'
              className={cn('size-36 [&_span]:text-muted-foreground')}
              showValue={false}
            />
            <div className='absolute inset-0 flex flex-col items-center justify-center'>
              <span className={cn('text-2xl font-semibold tabular-nums', color.text)}>
                {capacityKnown ? formatPercent(percent) : '—'}
              </span>
              <span className='text-xs text-muted-foreground'>
                {t('storageDialog.usedPercent')}
              </span>
            </div>
          </div>
        )}
        <div className='grid w-full min-w-0 grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4'>
          {[
            { label: t('storageDialog.used'), value: formatCapacityBytes(usedBytes) },
            {
              label: t('storageDialog.free'),
              value: capacityKnown ? formatCapacityBytes((totalBytes as number) - usedBytes) : '—',
            },
            {
              label: t('storageDialog.total'),
              value: capacityKnown ? formatCapacityBytes(totalBytes) : '—',
            },
            {
              label: t('storageDialog.files'),
              value: sysInfo?.storageType ?? '—',
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

      {!capacityKnown && !capacityLoading && (
        <div className='flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400'>
          <Info className='mt-0.5 size-3.5 shrink-0' />
          <span>{t('storageDialog.capacityUnknown')}</span>
        </div>
      )}

      <div className='grid gap-4 lg:grid-cols-2'>
        {/* 系统信息 */}
        <Panel title={t('storageDialog.systemInfo')} icon={Server}>
          {sysLoading || !sysInfo ? (
            <div className='space-y-2.5'>
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className='h-5 w-full' />
              ))}
            </div>
          ) : (
            <>
              <Row icon={Server} label={t('storageDialog.os')} value={sysInfo.osName ?? '—'} />
              <Row icon={Cpu} label={t('storageDialog.cpu')} value={
                sysInfo.cpuCores != null
                  ? t('storageDialog.cpuCores', { cores: sysInfo.cpuCores })
                  : '—'
              } />
              <Row
                icon={Cpu}
                label={t('storageDialog.cpuLoad')}
                value={formatPercent(sysInfo.systemCpuLoad ?? NaN)}
              />
              <Row
                icon={MemoryStick}
                label={t('storageDialog.jvmMemory')}
                value={
                  sysInfo.jvmMaxBytes != null
                    ? `${formatCapacityBytes(sysInfo.jvmUsedBytes)} / ${formatCapacityBytes(sysInfo.jvmMaxBytes)}`
                    : '—'
                }
              />
              <Row icon={Info} label={t('storageDialog.java')} value={sysInfo.javaVersion ?? '—'} mono />
            </>
          )}
        </Panel>

        {/* 运行信息 */}
        <Panel title={t('storageDialog.runtimeInfo')} icon={Clock3}>
          {sysLoading || !sysInfo ? (
            <div className='space-y-2.5'>
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className='h-5 w-full' />
              ))}
            </div>
          ) : (
            <>
              <Row icon={Clock3} label={t('storageDialog.startTime')} value={sysInfo.startTime ?? '—'} mono />
              <Row icon={Clock3} label={t('storageDialog.uptime')} value={sysInfo.uptimeText ?? '—'} />
              <Row icon={FolderTree} label={t('storageDialog.storageType')} value={sysInfo.storageType ?? '—'} />
              <Row
                icon={FolderTree}
                label={t('storageDialog.storagePath')}
                value={storagePath ?? '—'}
                mono
              />
            </>
          )}
        </Panel>
      </div>

      {/* 磁盘分区 */}
      {sysInfo?.disks && sysInfo.disks.length > 0 && (
        <Panel title={t('storageDialog.disks')} icon={HardDrive}>
          <div className='grid gap-3 sm:grid-cols-2 xl:grid-cols-3'>
            {sysInfo.disks.map((disk) => (
              <DiskCard
                key={disk.mountPoint}
                disk={disk}
                highlight={isCurrentDisk(disk)}
                t={t}
              />
            ))}
          </div>
        </Panel>
      )}
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
