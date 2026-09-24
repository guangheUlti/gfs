import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Clock3, Cpu, FolderTree, Info, MemoryStick, RefreshCw, Server } from 'lucide-react'
import {
  getJvmMemory,
  getSystemInfo,
  restartBackend,
  saveJvmMemory,
} from '@/api/home'
import { SettingsPageDescription, SettingsPageTitle } from '../components/settings-page-header'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { formatCapacityBytes } from '@/utils/format'
import { RestartOverlay } from './restart-overlay'

const MIN_MB = 256
const MAX_MB = 65536
const MB = 1024 * 1024

/** 运行环境 / 配置信息行 */
function InfoRow({
  icon: Icon,
  label,
  loading,
  value,
  mono = false,
}: {
  icon: React.ComponentType<{ className?: string }>
  label: string
  loading?: boolean
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div className='flex items-center gap-3 px-4 text-sm'>
      <Icon className='size-4 shrink-0 text-muted-foreground' strokeWidth={1.75} />
      <span className='shrink-0 text-muted-foreground'>{label}</span>
      {loading ? (
        <Skeleton className='h-4 w-20' />
      ) : (
        <span
          className={`ms-auto min-w-0 truncate text-right font-medium ${
            mono ? 'font-mono text-xs' : ''
          }`}
        >
          {value}
        </span>
      )}
    </div>
  )
}

/** 系统管理：查看服务运行环境，并调整 JVM 最大堆内存（调整需重启服务生效） */
export function SettingsSystemManagement() {
  const { t } = useTranslation('settings')
  const queryClient = useQueryClient()

  const [value, setValue] = useState('')
  const [confirmRestart, setConfirmRestart] = useState(false)
  const [restarting, setRestarting] = useState(false)
  const [restartOverlayOpen, setRestartOverlayOpen] = useState(false)

  const { data: sysInfo, isLoading: sysLoading } = useQuery({
    queryKey: ['systemInfo'],
    queryFn: getSystemInfo,
    staleTime: 30_000,
  })

  // 预填：已保存的配置值，否则取当前运行实例的最大堆（MB）
  useEffect(() => {
    getJvmMemory()
      .then((jvm) => {
        const seed =
          jvm?.configuredMaxMemoryMb ??
          (jvm?.runtimeMaxBytes ? Math.round(jvm.runtimeMaxBytes / MB) : '')
        setValue(seed !== null && seed !== undefined && seed !== '' ? String(seed) : '')
      })
      .catch(() => {
        /* 读不到配置时保持空值 */
      })
  }, [])

  /** 校验并转成可提交值：空 -> null（用 JVM 默认）；否则必须是范围区间内的整数 */
  const parseValue = (): number | null | 'invalid' => {
    const raw = value.trim()
    if (raw === '') return null
    if (!/^\d+$/.test(raw)) return 'invalid'
    const n = Number(raw)
    if (n < MIN_MB || n > MAX_MB) return 'invalid'
    return n
  }

  const saveMutation = useMutation({
    mutationFn: (mb: number | null) => saveJvmMemory(mb),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jvm-memory'] })
      toast.success(t('systemManagement.saved'))
    },
    onError: (err: any) => {
      if (!err?.handled) toast.error(t('systemManagement.saveFailed'))
    },
  })

  const restartMutation = useMutation({
    mutationFn: (mb: number | null) => restartBackend(mb),
    onSuccess: () => {
      // 响应送达仅代表重启指令已受理；遮罩在确认时已打开，后续由存活探测接管
      setConfirmRestart(false)
    },
    onError: (err: any) => {
      setConfirmRestart(false)
      if (err?.response) {
        // 后端仍在线并明确返回了错误（如写重启标记失败），按普通失败处理
        setRestartOverlayOpen(false)
        setRestarting(false)
        if (!err?.handled) toast.error(t('systemManagement.restartFailed'))
      }
      // 无 response = 连接被退出中的后端捶断，属重启预期：
      // 遮罩保持打开，由存活探测判定恢复
    },
  })

  const handleSave = () => {
    const parsed = parseValue()
    if (parsed === 'invalid') {
      toast.error(t('systemManagement.invalidValue'))
      return
    }
    saveMutation.mutate(parsed)
  }

  const handleRestart = () => {
    const parsed = parseValue()
    if (parsed === 'invalid') {
      toast.error(t('systemManagement.invalidValue'))
      return
    }
    setConfirmRestart(true)
  }

  const osMem = sysInfo?.osTotalMemoryBytes != null
    ? `${formatCapacityBytes(sysInfo.osUsedMemoryBytes ?? 0)} / ${formatCapacityBytes(sysInfo.osTotalMemoryBytes)}`
    : null

  return (
    <div className='flex flex-1 flex-col'>
      <SettingsPageTitle>{t('systemManagement.pageTitle')}</SettingsPageTitle>
      <SettingsPageDescription>
        {t('systemManagement.pageDescription')}
      </SettingsPageDescription>

      {/* 运行环境 */}
      <section className='mt-6 grid gap-4 lg:grid-cols-2'>
        {/* 系统信息 */}
        <div className='rounded-md border'>
          <div className='border-b px-4 py-3'>
            <div className='flex items-center gap-2'>
              <Server className='size-4 text-muted-foreground' strokeWidth={1.75} />
              <span className='text-sm font-medium'>
                {t('systemManagement.systemInfo')}
              </span>
            </div>
          </div>
          <div className='space-y-3 py-4'>
            <InfoRow icon={Server} label={t('systemManagement.os')} loading={sysLoading} value={sysInfo?.osName ?? '—'} />
            <InfoRow
              icon={Cpu}
              label={t('systemManagement.cpu')}
              loading={sysLoading}
              value={sysInfo?.cpuCores != null ? t('systemManagement.cpuCores', { cores: sysInfo.cpuCores }) : '—'}
            />
            <InfoRow icon={MemoryStick} label={t('systemManagement.osMemory')} loading={sysLoading} value={osMem ?? '—'} />
            <InfoRow
              icon={Cpu}
              label={t('systemManagement.cpuLoad')}
              loading={sysLoading}
              value={
                sysInfo?.systemCpuLoad != null
                  ? sysInfo.systemCpuLoad <= 0
                    ? '0%'
                    : `${sysInfo.systemCpuLoad.toFixed(1)}%`
                  : '—'
              }
            />
            <InfoRow icon={Info} label={t('systemManagement.java')} loading={sysLoading} value={sysInfo?.javaVersion ?? '—'} mono />
          </div>
        </div>

        {/* 运行信息 */}
        <div className='rounded-md border'>
          <div className='border-b px-4 py-3'>
            <div className='flex items-center gap-2'>
              <Clock3 className='size-4 text-muted-foreground' strokeWidth={1.75} />
              <span className='text-sm font-medium'>
                {t('systemManagement.runtimeInfo')}
              </span>
            </div>
          </div>
          <div className='space-y-3 py-4'>
            <InfoRow
              icon={MemoryStick}
              label={t('systemManagement.jvmMemory')}
              loading={sysLoading}
              value={
                sysInfo?.jvmMaxBytes != null
                  ? `${formatCapacityBytes(sysInfo.jvmUsedBytes)} / ${formatCapacityBytes(sysInfo.jvmMaxBytes)}`
                  : '—'
              }
            />
            <InfoRow
              icon={Clock3}
              label={t('systemManagement.startTime')}
              loading={sysLoading}
              value={sysInfo?.startTime ?? '—'}
              mono
            />
            <InfoRow icon={Clock3} label={t('systemManagement.uptime')} loading={sysLoading} value={sysInfo?.uptimeText ?? '—'} />
            <InfoRow icon={FolderTree} label={t('systemManagement.storageType')} loading={sysLoading} value={sysInfo?.storageType ?? '—'} />
            <InfoRow
              icon={FolderTree}
              label={t('systemManagement.storagePath')}
              loading={sysLoading}
              value={sysInfo?.storagePath ?? '—'}
              mono
            />
          </div>
        </div>
      </section>

      {/* JVM 最大堆内存配置与重启服务 */}
      <section className='mt-6 rounded-md border'>
        <div className='border-b px-4 py-3'>
          <div className='flex items-center gap-2'>
            <MemoryStick className='size-4 text-muted-foreground' strokeWidth={1.75} />
            <span className='text-sm font-medium'>
              {t('systemManagement.jvmSettings')}
            </span>
          </div>
        </div>
        <div className='px-4 py-4'>
          <div className='flex flex-wrap items-center gap-3'>
            <div className='flex min-w-0 items-center gap-2'>
              <Input
                value={value}
                onChange={(e) => setValue(e.target.value)}
                inputMode='numeric'
                placeholder={t('systemManagement.placeholder')}
                className='h-9 w-32 font-mono text-sm'
                disabled={restarting}
              />
              <span className='shrink-0 text-sm text-muted-foreground'>
                {t('systemManagement.unit')}
              </span>
            </div>
            <div className='flex flex-wrap items-center gap-2'>
              <Button
                variant='outline'
                size='sm'
                className='h-9'
                onClick={handleSave}
                disabled={restarting || saveMutation.isPending}
              >
                {t('systemManagement.save')}
              </Button>
              <Button
                size='sm'
                className='h-9'
                onClick={handleRestart}
                disabled={restarting || restartMutation.isPending}
              >
                <RefreshCw className={`size-4 ${restarting ? 'animate-spin' : ''}`} />
                {restarting
                  ? t('systemManagement.restarting')
                  : t('systemManagement.saveAndRestart')}
              </Button>
            </div>
          </div>
          <p className='mt-3 flex items-start gap-1.5 text-xs leading-relaxed text-muted-foreground'>
            <Info className='mt-0.5 size-3.5 shrink-0' />
            {t('systemManagement.hint')}
          </p>
        </div>
      </section>

      {/* 重启等待遮罩：虚拟进度 + 存活探测，成功后刷新页面数据 */}
      {restartOverlayOpen && (
        <RestartOverlay
          onClose={() => {
            setRestartOverlayOpen(false)
            setRestarting(false)
          }}
          onRecovered={() => {
            queryClient.invalidateQueries({ queryKey: ['systemInfo'] })
            queryClient.invalidateQueries({ queryKey: ['jvm-memory'] })
            getJvmMemory()
              .then((jvm) => {
                const seed =
                  jvm?.configuredMaxMemoryMb ??
                  (jvm?.runtimeMaxBytes
                    ? Math.round(jvm.runtimeMaxBytes / MB)
                    : '')
                setValue(
                  seed !== null && seed !== undefined && seed !== ''
                    ? String(seed)
                    : ''
                )
              })
              .catch(() => {
                /* 保持当前输入值 */
              })
          }}
        />
      )}

      <AlertDialog
        open={confirmRestart}
        onOpenChange={(open) => !open && setConfirmRestart(false)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('systemManagement.restartConfirmTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {t('systemManagement.restartConfirmDesc')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={restarting}>
              {t('systemManagement.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                // 确认即开遮罩：不依赖重启接口的响应，
                // 服务器网络延迟下后端可能先于响应退出
                setRestarting(true)
                setRestartOverlayOpen(true)
                restartMutation.mutate(parseValue() as number | null)
              }}
              disabled={restarting}
            >
              {t('systemManagement.restart')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}