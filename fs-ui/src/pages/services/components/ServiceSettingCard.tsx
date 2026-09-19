import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Globe, HardDrive, SquareTerminal } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import {
  serviceAction,
  updateServiceConfig,
  type ServiceSettingVO,
  type ServiceAction,
} from '@/api/service'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { ConfirmDialog } from '@/components/confirm-dialog'
import { useServiceSettings } from '../hooks/useServiceSettings'
import { ServiceStatusBadge } from './ServiceStatusBadge'

interface ServiceSettingCardProps {
  setting: ServiceSettingVO
  children?: ReactNode
}

export function ServiceSettingCard({ setting, children }: ServiceSettingCardProps) {
  const { t } = useTranslation('services')
  const queryClient = useQueryClient()
  // socket 型服务（sftp/ftp）才有端口/地址表单与「保存」按钮；webdav 仅开关语义
  const socketBased = setting.port != null

  const [port, setPort] = useState(String(setting.port ?? 9022))
  const [bindAddress, setBindAddress] = useState(setting.bindAddress || '0.0.0.0')
  const [portError, setPortError] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [pendingEnabled, setPendingEnabled] = useState(false)

  // 后端热生效后状态经轮询返回；配置变更时同步本地表单
  useEffect(() => {
    setPort(String(setting.port ?? 9022))
    setBindAddress(setting.bindAddress || '0.0.0.0')
  }, [setting.port, setting.bindAddress])

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['serviceSettings'] })
  }

  const configMutation = useMutation({
    mutationFn: (enabled: boolean) =>
      updateServiceConfig(setting.serviceType, {
        enabled,
        ...(socketBased ? { port: Number(port), bindAddress: bindAddress.trim() } : {}),
      }),
    onSuccess: () => {
      toast.success(t('toast.saveOk'))
      invalidate()
    },
  })

  const actionMutation = useMutation({
    mutationFn: (action: ServiceAction) =>
      serviceAction(setting.serviceType, action),
    onSuccess: (_data, action) => {
      toast.success(
        action === 'start'
          ? t('toast.startOk')
          : action === 'stop'
            ? t('toast.stopOk')
            : t('toast.restartOk')
      )
      invalidate()
    },
  })

  const busy = configMutation.isPending || actionMutation.isPending

  const validatePort = (): boolean => {
    if (!socketBased) return true
    const n = Number(port)
    if (!Number.isInteger(n) || n < 1024 || n > 65535) {
      setPortError(t('form.portInvalid'))
      return false
    }
    setPortError('')
    return true
  }

  /** 开关切换统一走确认对话框（参照存储卡片流程） */
  const handleToggle = (next: boolean) => {
    setPendingEnabled(next)
    setConfirmOpen(true)
  }

  const handleConfirmToggle = () => {
    if (!validatePort()) {
      setConfirmOpen(false)
      return
    }
    configMutation.mutate(pendingEnabled, {
      onSettled: () => setConfirmOpen(false),
    })
  }

  const handleSave = () => {
    if (!validatePort()) return
    configMutation.mutate(setting.enabled)
  }

  const handleAction = (action: ServiceAction) => actionMutation.mutate(action)

  const isRunning = setting.status === 'running'
  const isBusyAction = actionMutation.isPending
  const toggling = configMutation.isPending

  return (
    <div className='flex flex-col rounded-lg border bg-card text-card-foreground shadow-sm'>
      {/* 头部：图标 + 名称 + 状态徽标 */}
      <div className='flex items-start justify-between gap-3 border-b px-5 py-4'>
        <div className='flex min-w-0 items-center gap-3'>
          <div className='flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-muted'>
            {setting.serviceType === 'sftp' ? (
              <SquareTerminal className='h-5 w-5 text-muted-foreground' />
            ) : setting.serviceType === 'ftp' ? (
              <HardDrive className='h-5 w-5 text-muted-foreground' />
            ) : (
              <Globe className='h-5 w-5 text-muted-foreground' />
            )}
          </div>
          <div className='min-w-0'>
            <h3 className='truncate font-semibold uppercase'>
              {setting.serviceType}
            </h3>
            <p className='truncate text-xs text-muted-foreground'>
              {t(`page.${setting.serviceType}Desc`)}
            </p>
          </div>
        </div>
        <ServiceStatusBadge setting={setting} />
      </div>

      {/* 配置表单：flex-1 撑开，让两张卡在网格里拉伸到等高、操作区贴底 */}
      <div className='flex-1 space-y-4 px-5 py-4'>
        <div className='flex items-center justify-between'>
          <Label htmlFor={`${setting.serviceType}-enabled`} className='cursor-pointer'>
            {t('form.enabled')}
          </Label>
          <Switch
            id={`${setting.serviceType}-enabled`}
            checked={setting.enabled}
            disabled={toggling || isBusyAction}
            onCheckedChange={handleToggle}
          />
        </div>

        {socketBased && (
          <>
            <div className='space-y-1.5'>
              <Label htmlFor={`${setting.serviceType}-port`}>{t('form.port')}</Label>
              <Input
                id={`${setting.serviceType}-port`}
                type='number'
                min={1024}
                max={65535}
                placeholder={t('form.portPh')}
                value={port}
                onChange={(e) => setPort(e.target.value)}
              />
              {portError ? (
                <p className='text-xs text-destructive'>{portError}</p>
              ) : (
                <p className='text-xs text-muted-foreground'>{t('form.portHint')}</p>
              )}
            </div>
            <div className='space-y-1.5'>
              <Label htmlFor={`${setting.serviceType}-bind`}>{t('form.bindAddress')}</Label>
              <Input
                id={`${setting.serviceType}-bind`}
                placeholder={t('form.bindAddressPh')}
                value={bindAddress}
                onChange={(e) => setBindAddress(e.target.value)}
              />
              <p className='text-xs text-muted-foreground'>
                {t('form.bindAddressHint')}
              </p>
            </div>
          </>
        )}

        {setting.status === 'error' && setting.error && (
          <p className='rounded-md bg-destructive/10 px-3 py-2 text-xs break-all text-destructive'>
            {setting.error}
          </p>
        )}
      </div>

      {children}

      {/* 操作区 */}
      <div className='flex items-center gap-2 border-t px-5 py-3'>
        {socketBased && (
          <Button
            size='sm'
            disabled={toggling || isBusyAction}
            onClick={handleSave}
          >
            {configMutation.isPending ? t('actions.saving') : t('actions.save')}
          </Button>
        )}
        {!setting.enabled ? (
          <Button
            size='sm'
            variant='outline'
            disabled={busy}
            onClick={() => handleToggle(true)}
          >
            {t('actions.start')}
          </Button>
        ) : (
          <>
            <Button
              size='sm'
              variant='outline'
              disabled={toggling || isBusyAction}
              onClick={() => handleToggle(false)}
            >
              {t('actions.stop')}
            </Button>
            <Button
              size='sm'
              variant='outline'
              disabled={toggling || isBusyAction}
              onClick={() => handleAction('restart')}
            >
              {actionMutation.isPending && actionMutation.variables === 'restart'
                ? t('actions.restarting')
                : t('actions.restart')}
            </Button>
          </>
        )}
      </div>

      {/* 启停确认对话框 */}
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={
          pendingEnabled ? t('dialog.enableTitle') : t('dialog.disableTitle')
        }
        desc={pendingEnabled ? t('dialog.enableDesc') : t('dialog.disableDesc')}
        confirmText={
          pendingEnabled
            ? t('dialog.confirmEnable')
            : t('dialog.confirmDisable')
        }
        isLoading={toggling}
        handleConfirm={handleConfirmToggle}
      />
    </div>
  )
}
