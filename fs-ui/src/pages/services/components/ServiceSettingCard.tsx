import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Cloud, Globe, HardDrive, SquareTerminal, Tv } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import {
  listAlistGuestUsers,
  serviceAction,
  updateServiceConfig,
  type ServiceSettingVO,
  type ServiceAction,
} from '@/api/service'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { ConfirmDialog } from '@/components/confirm-dialog'
import { useActiveStoragePlatforms } from '@/hooks/use-current-storage'
import { useServiceSettings } from '../hooks/useServiceSettings'
import { OssKeyManager } from './OssKeyManager'
import { ServiceStatusBadge } from './ServiceStatusBadge'
import { WebDavAccessInfo } from './WebDavAccessInfo'

interface ServiceSettingCardProps {
  setting: ServiceSettingVO
  children?: ReactNode
}

export function ServiceSettingCard({ setting, children }: ServiceSettingCardProps) {
  const { t } = useTranslation('services')
  const queryClient = useQueryClient()
  // socket 型服务（sftp/ftp）才有端口/地址表单与「保存」按钮；webdav 仅开关语义
  const socketBased = setting.port != null
  // 游客访问只对 AList 有意义：影视仓/TVBox 这类客户端不登录
  const isAlist = setting.serviceType === 'alist'
  // WebDAV 是固定 /dav 入口，卡片里给出可复制的地址与当前能力
  const isWebdav = setting.serviceType === 'webdav'

  const [port, setPort] = useState(String(setting.port ?? 9022))
  const [bindAddress, setBindAddress] = useState(setting.bindAddress || '0.0.0.0')
  const [guestUserId, setGuestUserId] = useState(setting.guestUserId ?? '')
  const [portError, setPortError] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [pendingEnabled, setPendingEnabled] = useState(false)

  const guestUsersQuery = useQuery({
    queryKey: ['alistGuestUsers'],
    queryFn: listAlistGuestUsers,
    enabled: isAlist,
  })
  const guestUsers = guestUsersQuery.data ?? []

  const platformsQuery = useActiveStoragePlatforms()
  const platforms = platformsQuery.data ?? []

  // 后端热生效后状态经轮询返回；配置变更时同步本地表单
  useEffect(() => {
    setPort(String(setting.port ?? 9022))
    setBindAddress(setting.bindAddress || '0.0.0.0')
  }, [setting.port, setting.bindAddress])

  useEffect(() => {
    setGuestUserId(setting.guestUserId ?? '')
  }, [setting.guestUserId])

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['serviceSettings'] })
  }

  const configMutation = useMutation({
    mutationFn: (params: {
      enabled: boolean
      readOnly: boolean
      allowGuest?: boolean
      guestUserId?: string
      guestPassword?: boolean
      storageSettingId?: string
    }) =>
      updateServiceConfig(setting.serviceType, {
        enabled: params.enabled,
        readOnly: params.readOnly,
        ...(params.storageSettingId !== undefined
          ? { storageSettingId: params.storageSettingId }
          : {}),
        ...(isAlist
          ? {
              allowGuest: params.allowGuest ?? setting.allowGuest,
              guestUserId: params.guestUserId ?? guestUserId,
              guestPassword: params.guestPassword ?? setting.guestPassword,
            }
          : {}),
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
    configMutation.mutate(
      { enabled: pendingEnabled, readOnly: setting.readOnly },
      { onSettled: () => setConfirmOpen(false) }
    )
  }

  const handleSave = () => {
    if (!validatePort()) return
    configMutation.mutate({ enabled: setting.enabled, readOnly: setting.readOnly })
  }

  /**
   * 服务存储即时保存：外部协议按服务级配置解析，下一次请求就落到新存储；
   * 缓存随保存失效，无需重启服务
   */
  const handleStorageChange = (id: string) => {
    configMutation.mutate({
      enabled: setting.enabled,
      readOnly: setting.readOnly,
      storageSettingId: id,
    })
  }

  /** 只读开关即时保存：可逆的读权限收紧/放开，不需要二次确认 */
  const handleReadOnlyToggle = (next: boolean) => {
    configMutation.mutate({ enabled: setting.enabled, readOnly: next })
  }

  /** 游客访问开关即时保存：未选公开账号时先提示，避免后端拒绝 */
  const handleGuestToggle = (next: boolean) => {
    if (next && !guestUserId) {
      toast.error(t('form.guestUserRequired'))
      return
    }
    configMutation.mutate({
      enabled: setting.enabled,
      readOnly: setting.readOnly,
      allowGuest: next,
      guestUserId,
    })
  }

  /** 公开账号变更即时保存：游客已开启时切换账号立即生效 */
  const handleGuestUserChange = (id: string) => {
    setGuestUserId(id)
    configMutation.mutate({
      enabled: setting.enabled,
      readOnly: setting.readOnly,
      guestUserId: id,
    })
  }

  /** 目录密码开关即时保存：密码校验在接口入口，保存后下一条请求就生效 */
  const handleGuestPasswordToggle = (next: boolean) => {
    configMutation.mutate({
      enabled: setting.enabled,
      readOnly: setting.readOnly,
      guestPassword: next,
    })
  }

  const handleAction = (action: ServiceAction) => actionMutation.mutate(action)

  const isRunning = setting.status === 'running'
  const isBusyAction = actionMutation.isPending
  const toggling = configMutation.isPending
  // 已绑定账号但不在可选列表（被禁用/删除）时给出显式警告，避免游客模式静默失效
  const guestUserMissing = Boolean(
    setting.guestUserId &&
      guestUsersQuery.isSuccess &&
      !guestUsers.some((user) => user.id === setting.guestUserId)
  )
  // 已选存储被停用/删除时后端回退到内置系统存储，这里显式提示避免静默换盘
  const storageMissing = Boolean(
    setting.storageSettingId &&
      setting.storageSettingId !== 'Local' &&
      platformsQuery.isSuccess &&
      !platforms.some((p) => p.settingId === setting.storageSettingId)
  )

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
            ) : setting.serviceType === 'oss' ? (
              <Cloud className='h-5 w-5 text-muted-foreground' />
            ) : setting.serviceType === 'alist' ? (
              <Tv className='h-5 w-5 text-muted-foreground' />
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

        <div className='space-y-1.5'>
          <Label>{t('form.storage')}</Label>
          <Select
            value={setting.storageSettingId || 'Local'}
            onValueChange={handleStorageChange}
            disabled={toggling || isBusyAction || platformsQuery.isLoading}
          >
            <SelectTrigger className='w-full'>
              <SelectValue placeholder={t('form.storagePh')} />
            </SelectTrigger>
            <SelectContent>
              {platforms.map((platform) => (
                <SelectItem key={platform.settingId} value={platform.settingId}>
                  {platform.platformName}
                  {platform.statelessRemote ? ` · ${t('form.storageMount')}` : ''}
                  {platform.settingId !== 'Local' && platform.remark
                    ? ` · ${platform.remark}`
                    : ''}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {storageMissing ? (
            <p className='text-xs text-destructive'>
              {t('form.storageInvalid')}
            </p>
          ) : (
            <p className='text-xs text-muted-foreground'>{t('form.storageHint')}</p>
          )}
        </div>

        <div className='space-y-1.5'>
          <div className='flex items-center justify-between'>
            <Label htmlFor={`${setting.serviceType}-read-only`} className='cursor-pointer'>
              {t('form.readOnly')}
            </Label>
            <Switch
              id={`${setting.serviceType}-read-only`}
              checked={setting.readOnly}
              disabled={toggling || isBusyAction}
              onCheckedChange={handleReadOnlyToggle}
            />
          </div>
          <p className='text-xs text-muted-foreground'>{t('form.readOnlyHint')}</p>
        </div>

        {isWebdav && <WebDavAccessInfo readOnly={setting.readOnly} />}

        {isAlist && (
          <div className='space-y-3 rounded-md border border-dashed px-3 py-3'>
            <div className='flex items-center justify-between'>
              <Label htmlFor='alist-allow-guest' className='cursor-pointer'>
                {t('form.allowGuest')}
              </Label>
              <Switch
                id='alist-allow-guest'
                checked={setting.allowGuest}
                disabled={toggling || isBusyAction || !guestUserId}
                onCheckedChange={handleGuestToggle}
              />
            </div>
            <p className='text-xs text-muted-foreground'>
              {t('form.allowGuestHint')}
            </p>
            <div className='space-y-1.5'>
              <Label>{t('form.guestUser')}</Label>
              <Select
                value={guestUserId || undefined}
                onValueChange={handleGuestUserChange}
                disabled={toggling || isBusyAction || guestUsersQuery.isLoading}
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder={t('form.guestUserPh')} />
                </SelectTrigger>
                <SelectContent>
                  {guestUsers.map((user) => (
                    <SelectItem key={user.id} value={user.id}>
                      {user.username}
                      {user.nickname ? ` (${user.nickname})` : ''}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {guestUserMissing ? (
                <p className='text-xs text-destructive'>
                  {t('form.guestUserInvalid')}
                </p>
              ) : (
                <p className='text-xs text-muted-foreground'>
                  {t('form.guestUserHint')}
                </p>
              )}
            </div>
            <div className='space-y-1.5 border-t border-dashed pt-3'>
              <div className='flex items-center justify-between'>
                <Label htmlFor='alist-guest-password' className='cursor-pointer'>
                  {t('form.guestPassword')}
                </Label>
                <Switch
                  id='alist-guest-password'
                  checked={setting.guestPassword}
                  disabled={toggling || isBusyAction || !setting.allowGuest}
                  onCheckedChange={handleGuestPasswordToggle}
                />
              </div>
              <p className='text-xs text-muted-foreground'>
                {t('form.guestPasswordHint')}
              </p>
            </div>
          </div>
        )}

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

      {setting.serviceType === 'oss' ? <OssKeyManager serviceEnabled={setting.enabled} /> : children}

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
