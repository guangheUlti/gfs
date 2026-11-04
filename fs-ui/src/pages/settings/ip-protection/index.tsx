import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Plus, Trash2 } from 'lucide-react'
import { adminApi, isHandledError } from '@/api'
import { cn } from '@/lib/utils'
import type { IpGuardMode, IpGuardRule } from '@/types/security'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
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
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'

const MAX_RULES = 200

/** IPv4 精确地址（网段掩码单独校验，见 validateEntry） */
const IPV4_RE =
  /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/

/**
 * 条目格式预检（只为即时反馈，最终判定仍在后端）：
 * IPv4 走精确正则；IPv6 只查字符集与掩码长度，避免在前端重复实现一遍完整解析器。
 */
function validateEntry(entry: string): boolean {
  const text = entry.trim()
  if (text === '') return true
  const parts = text.split('/')
  if (parts.length > 2) return false
  const host = parts[0]
  const isIpv6 = host.includes(':')
  if (isIpv6) {
    if (!/^[0-9a-fA-F:.]+$/.test(host)) return false
  } else if (!IPV4_RE.test(host)) {
    return false
  }
  if (parts.length === 1) return true
  if (!/^\d{1,3}$/.test(parts[1])) return false
  const bits = Number(parts[1])
  return bits <= (isIpv6 ? 128 : 32)
}

const MODES: IpGuardMode[] = ['OFF', 'WHITE', 'BLACK']

/** 安全防护：按来源 IP 限制登录与使用，白名单与黑名单互斥 */
export function SettingsIpProtection() {
  const { t } = useTranslation('settings')
  const queryClient = useQueryClient()

  const [mode, setMode] = useState<IpGuardMode>('OFF')
  const [whiteList, setWhiteList] = useState<IpGuardRule[]>([])
  const [blackList, setBlackList] = useState<IpGuardRule[]>([])
  const [currentIp, setCurrentIp] = useState('')
  const [saved, setSaved] = useState({
    mode: 'OFF' as IpGuardMode,
    white: '',
    black: '',
  })
  const [confirmMode, setConfirmMode] = useState<IpGuardMode | null>(null)

  const { isLoading } = useQuery({
    queryKey: ['ipGuard'],
    queryFn: async () => {
      const data = await adminApi.getIpGuard()
      setMode(data.mode)
      setCurrentIp(data.currentIp ?? '')
      const white = data.whiteList ?? []
      const black = data.blackList ?? []
      setWhiteList(white)
      setBlackList(black)
      setSaved({
        mode: data.mode,
        white: JSON.stringify(white),
        black: JSON.stringify(black),
      })
      return data
    },
  })

  const dirty =
    mode !== saved.mode ||
    JSON.stringify(whiteList) !== saved.white ||
    JSON.stringify(blackList) !== saved.black

  const saveMutation = useMutation({
    mutationFn: () =>
      adminApi.saveIpGuard({
        mode,
        whiteList: whiteList.filter((rule) => rule.ip.trim() !== ''),
        blackList: blackList.filter((rule) => rule.ip.trim() !== ''),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ipGuard'] })
      toast.success(t('ipProtection.saved'))
    },
    onError: (err) => {
      // 拦截器已经按后端 msg 弹过（如「白名单未包含你当前的 IP」），这里只兜底未提示过的错误
      if (!isHandledError(err)) toast.error(t('ipProtection.saveFailed'))
    },
  })

  const invalidCount =
    whiteList.filter((rule) => !validateEntry(rule.ip)).length +
    blackList.filter((rule) => !validateEntry(rule.ip)).length

  const handleSave = () => {
    if (invalidCount > 0) {
      toast.error(t('ipProtection.invalidEntry'))
      return
    }
    if (whiteList.length > MAX_RULES || blackList.length > MAX_RULES) {
      toast.error(t('ipProtection.ruleLimit', { max: MAX_RULES }))
      return
    }
    // 启用名单 = 可能把自己挡在外面，值得一次显式确认（后端还会再拦一道）
    if (mode !== 'OFF' && mode !== saved.mode) {
      setConfirmMode(mode)
      return
    }
    saveMutation.mutate()
  }

  const updateRule = (
    list: IpGuardRule[],
    setter: (next: IpGuardRule[]) => void,
    index: number,
    patch: Partial<IpGuardRule>
  ) => {
    const next = [...list]
    next[index] = { ...next[index], ...patch }
    setter(next)
  }

  const addEmptyRule = (
    list: IpGuardRule[],
    setter: (next: IpGuardRule[]) => void
  ) => {
    if (list.length >= MAX_RULES) {
      toast.error(t('ipProtection.ruleLimit', { max: MAX_RULES }))
      return
    }
    setter([...list, { ip: '', remark: '' }])
  }

  const addCurrentIp = (
    list: IpGuardRule[],
    setter: (next: IpGuardRule[]) => void
  ) => {
    if (!currentIp) return
    if (list.some((rule) => rule.ip.trim() === currentIp)) {
      toast.message(t('ipProtection.alreadyInList'))
      return
    }
    setter([...list, { ip: currentIp, remark: '' }])
  }

  const renderList = (
    key: 'white' | 'black',
    list: IpGuardRule[],
    setter: (next: IpGuardRule[]) => void
  ) => {
    const active = mode === (key === 'white' ? 'WHITE' : 'BLACK')
    return (
      <section key={key} className='rounded-md border'>
        <div className='flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3'>
          <div className='min-w-0 space-y-1'>
            <div className='flex items-center gap-2'>
              <p className='text-sm font-medium'>
                {t(key === 'white' ? 'ipProtection.whiteTitle' : 'ipProtection.blackTitle')}
              </p>
              <Badge variant={active ? 'default' : 'secondary'}>
                {t(active ? 'ipProtection.activeBadge' : 'ipProtection.inactiveBadge')}
              </Badge>
            </div>
            <p className='text-xs leading-relaxed text-muted-foreground'>
              {t(
                key === 'white'
                  ? 'ipProtection.whiteDescription'
                  : 'ipProtection.blackDescription'
              )}
            </p>
          </div>
          <Button
            variant='outline'
            size='sm'
            disabled={!currentIp}
            onClick={() => addCurrentIp(list, setter)}
          >
            {t('ipProtection.addCurrentIp')}
          </Button>
        </div>
        <div className='space-y-2 px-4 py-4'>
          {list.length === 0 ? (
            <p className='text-sm text-muted-foreground'>
              {t('ipProtection.emptyList')}
            </p>
          ) : (
            list.map((rule, index) => {
              const valid = validateEntry(rule.ip)
              return (
                <div key={index} className='flex flex-wrap items-center gap-2'>
                  <Input
                    value={rule.ip}
                    aria-invalid={!valid}
                    onChange={(e) =>
                      updateRule(list, setter, index, { ip: e.target.value })
                    }
                    placeholder={t('ipProtection.placeholderIp')}
                    className={cn(
                      'min-w-40 flex-1 font-mono',
                      !valid && 'border-destructive'
                    )}
                  />
                  <Input
                    value={rule.remark ?? ''}
                    onChange={(e) =>
                      updateRule(list, setter, index, {
                        remark: e.target.value,
                      })
                    }
                    placeholder={t('ipProtection.placeholderRemark')}
                    className='min-w-32 flex-1 sm:max-w-48'
                  />
                  <Button
                    variant='ghost'
                    size='icon'
                    aria-label={t('ipProtection.deleteRule')}
                    onClick={() =>
                      setter(list.filter((_, i) => i !== index))
                    }
                  >
                    <Trash2 className='size-4' />
                  </Button>
                </div>
              )
            })
          )}
          {!active ? (
            <p className='text-xs text-muted-foreground'>
              {t('ipProtection.inactiveListHint')}
            </p>
          ) : null}
          <Button variant='outline' size='sm' onClick={() => addEmptyRule(list, setter)}>
            <Plus className='size-4' />
            {t('ipProtection.addRule')}
          </Button>
        </div>
      </section>
    )
  }

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex flex-wrap items-start justify-between gap-4'>
        <div className='min-w-0 space-y-1'>
          <SettingsPageTitle>
            {t('ipProtection.pageTitle')}
          </SettingsPageTitle>
          <SettingsPageDescription>
            {t('ipProtection.pageDescription')}
          </SettingsPageDescription>
        </div>
        <div className='flex items-center gap-2'>
          {invalidCount > 0 ? (
            <span className='text-xs text-destructive'>
              {t('ipProtection.invalidEntry')}
            </span>
          ) : dirty ? (
            <span className='text-xs text-muted-foreground'>
              {t('ipProtection.dirtyHint')}
            </span>
          ) : null}
          <Button
            onClick={handleSave}
            disabled={isLoading || !dirty || saveMutation.isPending}
          >
            {saveMutation.isPending
              ? t('ipProtection.saving')
              : t('ipProtection.save')}
          </Button>
        </div>
      </header>

      <div className='mt-6 space-y-6'>
        <section className='rounded-md border'>
          <div className='border-b px-4 py-3'>
            <p className='text-sm font-medium'>{t('ipProtection.mode')}</p>
            <p className='mt-1 text-xs leading-relaxed text-muted-foreground'>
              {t('ipProtection.modeDescription')}
            </p>
          </div>
          <RadioGroup
            className='gap-0 px-4 py-2'
            value={mode}
            onValueChange={(value) => setMode(value as IpGuardMode)}
          >
            {MODES.map((item) => (
              <Label
                key={item}
                htmlFor={`ip-guard-mode-${item}`}
                className='flex cursor-pointer items-start gap-3 border-b py-3 text-sm last:border-b-0'
              >
                <RadioGroupItem
                  id={`ip-guard-mode-${item}`}
                  value={item}
                  className='mt-0.5'
                />
                <span className='min-w-0 space-y-0.5'>
                  <span>{t(`ipProtection.mode${item}`)}</span>
                  <span className='block text-xs text-muted-foreground'>
                    {t(`ipProtection.mode${item}Desc`)}
                  </span>
                </span>
              </Label>
            ))}
          </RadioGroup>
          <div className='flex flex-wrap items-center gap-x-3 gap-y-1 border-t px-4 py-3 text-sm'>
            <span className='text-muted-foreground'>
              {t('ipProtection.currentIp')}
            </span>
            <span className='font-mono'>{currentIp || '—'}</span>
            <span className='text-xs text-muted-foreground'>
              {t('ipProtection.currentIpHint')}
            </span>
          </div>
        </section>

        {/* 两份名单都展示：生效的那份排在上面，另一份保留内容并标注未启用，切换模式不必重新录入 */}
        {mode === 'BLACK'
          ? [
              renderList('black', blackList, setBlackList),
              renderList('white', whiteList, setWhiteList),
            ]
          : [
              renderList('white', whiteList, setWhiteList),
              renderList('black', blackList, setBlackList),
            ]}
      </div>

      <AlertDialog
        open={confirmMode !== null}
        onOpenChange={(open) => !open && setConfirmMode(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('ipProtection.confirmTitle', {
                name: t(`ipProtection.mode${confirmMode ?? 'OFF'}`),
              })}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {t('ipProtection.confirmDesc')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('ipProtection.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setConfirmMode(null)
                saveMutation.mutate()
              }}
            >
              {t('ipProtection.confirmAction')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
