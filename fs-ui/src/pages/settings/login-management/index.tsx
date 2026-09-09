import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { RefreshCw, Search } from 'lucide-react'
import { RiComputerLine } from '@remixicon/react'
import dayjs from 'dayjs'
import { adminApi } from '@/api'
import type { OnlineTerminal, OnlineUser } from '@/types/user'
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import {
  Avatar,
  AvatarFallback,
  AvatarImage,
} from '@/components/ui/avatar'
import {
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHead,
  TableRow,
} from '@/components/ui/table'
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
import { useAuth } from '@/contexts/auth-context'

/** 列表是实时状态，隔一段时间自动刷新，避免管理员对着过期数据踢人 */
const AUTO_REFRESH_INTERVAL = 30_000

/** 后端对无法识别的 UA 会给出 Unknown 或 未知，展示时统一按未知处理（含改动前留下的脏数据） */
const UNKNOWN_DEVICE_RE = /^(unknown|未知)/i

type KickTarget =
  | { type: 'terminal'; user: OnlineUser; terminal: OnlineTerminal }
  | { type: 'user'; user: OnlineUser }

/** 该账号的会话里是否包含管理员自己正在用的这个 */
const hasCurrentTerminal = (user: OnlineUser) =>
  (user.terminals ?? []).some((term) => term.current)

export function SettingsLoginManagement() {
  const { t } = useTranslation('settings')
  const { logout, token } = useAuth()
  const [users, setUsers] = useState<OnlineUser[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [actingKey, setActingKey] = useState<string | null>(null)
  const [target, setTarget] = useState<KickTarget | null>(null)

  const fetchSessions = useCallback(
    async (kw?: string) => {
      setLoading(true)
      try {
        const data = await adminApi.listOnlineSessions(kw?.trim() || undefined)
        setUsers(data ?? [])
      } catch (err: any) {
        if (!err?.handled) toast.error(t('loginManagement.listFailed'))
      } finally {
        setLoading(false)
      }
    },
    [t]
  )

  useEffect(() => {
    fetchSessions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 已被踢下线时不要再继续轮询，否则会被拦截器直接跳到登录页
  const keywordRef = useRef(keyword)
  keywordRef.current = keyword
  useEffect(() => {
    if (!token) return
    const timer = setInterval(
      () => fetchSessions(keywordRef.current),
      AUTO_REFRESH_INTERVAL
    )
    return () => clearInterval(timer)
  }, [fetchSessions, token])

  const rows = useMemo(
    () =>
      users.flatMap((user) =>
        (user.terminals ?? []).map((terminal, i) => ({
          user,
          terminal,
          first: i === 0,
        }))
      ),
    [users]
  )

  // 后端存的是 UA 解析的全称（浏览器带完整版本号、Windows 全拼），
  // 在展示层缩短，历史会话里的旧值也一并生效
  const shortenDevice = (value: string) =>
    value
      .replace(/^(?:Microsoft |MS ?)Edge/i, 'Edge')
      .replace(/(\d+)(\.\d+)+$/, '$1')
      .replace(/^Mac OS X/i, 'macOS')
      .replace(/^Windows /i, 'Win ')

  const deviceLabel = (terminal: OnlineTerminal) => {
    const parts = [terminal.browser, terminal.os]
      .filter((v) => v && !UNKNOWN_DEVICE_RE.test(v.trim()))
      .map((v) => shortenDevice(v.trim()))
    return parts.length > 0 ? parts.join(' · ') : t('loginManagement.unknownDevice')
  }

  const agoLabel = (ms?: number) => {
    if (!ms) return '—'
    const diff = Date.now() - ms
    if (diff < 60_000) return t('loginManagement.justNow')
    if (diff < 3_600_000) {
      return t('loginManagement.minutesAgo', { n: Math.floor(diff / 60_000) })
    }
    if (diff < 86_400_000) {
      return t('loginManagement.hoursAgo', { n: Math.floor(diff / 3_600_000) })
    }
    return t('loginManagement.daysAgo', { n: Math.floor(diff / 86_400_000) })
  }

  const statusLabel = (status?: number) => {
    switch (status) {
      case 1:
        return t('loginManagement.statusDisabled')
      case 2:
        return t('loginManagement.statusPending')
      case 3:
        return t('loginManagement.statusRejected')
      default:
        return null
    }
  }

  const handleKick = async () => {
    if (!target) return
    const { user } = target
    const actingId =
      target.type === 'terminal'
        ? `${user.loginId}:${target.terminal.index}`
        : `${user.loginId}:*`
    const willLoseSelf =
      target.type === 'user'
        ? hasCurrentTerminal(user)
        : !!target.terminal.current
    setActingKey(actingId)
    try {
      if (target.type === 'terminal') {
        await adminApi.kickoutTerminal(
          user.loginId,
          target.terminal.index,
          target.terminal.tokenTail
        )
        toast.success(t('loginManagement.kicked'))
      } else {
        const count = await adminApi.kickoutUser(user.loginId)
        toast.success(
          t('loginManagement.kickedAll', {
            n: count ?? user.terminals?.length ?? 0,
          })
        )
      }
      setTarget(null)
      if (willLoseSelf) {
        // 自己也被踢了：服务端 token 已失效，直接回到登录态，别再发无意义的请求
        logout()
        return
      }
      fetchSessions(keyword)
    } catch (err: any) {
      if (!err?.handled) toast.error(t('loginManagement.actionFailed'))
      setTarget(null)
      // 失败多半是列表已过期（会话变化导致序号错位），顺手刷一次
      fetchSessions(keyword)
    } finally {
      setActingKey(null)
    }
  }

  const confirmTitle = () => {
    if (!target) return ''
    if (target.type === 'user') return t('loginManagement.confirmKickAllTitle')
    return target.terminal.current
      ? t('loginManagement.confirmKickSelfTitle')
      : t('loginManagement.confirmKickTitle')
  }

  const confirmDesc = () => {
    if (!target) return ''
    const name = target.user.nickname || target.user.username || ''
    if (target.type === 'user') {
      const base = t('loginManagement.confirmKickAllDesc', {
        name,
        n: target.user.terminals?.length ?? 0,
      })
      return hasCurrentTerminal(target.user)
        ? `${base}${t('loginManagement.confirmKickAllSelfWarning')}`
        : base
    }
    return target.terminal.current
      ? t('loginManagement.confirmKickSelfDesc')
      : t('loginManagement.confirmKickDesc', { name })
  }

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex flex-wrap items-start justify-between gap-4'>
        <div>
          <SettingsPageTitle>{t('loginManagement.pageTitle')}</SettingsPageTitle>
          <SettingsPageDescription>
            {t('loginManagement.pageDescription')}
          </SettingsPageDescription>
        </div>
        <div className='flex shrink-0 items-center gap-2'>
          <form
            className='relative'
            onSubmit={(e) => {
              e.preventDefault()
              fetchSessions(keyword)
            }}
          >
            <Search className='absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground' />
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder={t('loginManagement.searchPlaceholder')}
              className='h-8 w-40 pl-8 text-sm sm:w-48'
            />
          </form>
          <Button
            variant='ghost'
            size='icon'
            className='size-8'
            onClick={() => fetchSessions(keyword)}
            disabled={loading}
            aria-label={t('loginManagement.refresh')}
          >
            <RefreshCw
              className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`}
            />
          </Button>
        </div>
      </header>

      <div className='mt-8 flex-1'>
        {/* 桌面端表格 */}
        <div className='hidden rounded-md border md:block'>
          <Table className='min-w-[760px]'>
            <TableHeader>
              <TableRow>
                <TableHead>{t('loginManagement.colUser')}</TableHead>
                <TableHead>{t('loginManagement.colDevice')}</TableHead>
                <TableHead className='whitespace-nowrap'>
                  {t('loginManagement.colLoginAt')}
                </TableHead>
                <TableHead className='whitespace-nowrap'>
                  {t('loginManagement.colLastActive')}
                </TableHead>
                <TableHead className='w-44'>
                  {t('loginManagement.colActions')}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && rows.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('loginManagement.loading')}
                  </TableCell>
                </TableRow>
              ) : rows.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('loginManagement.empty')}
                  </TableCell>
                </TableRow>
              ) : (
                rows.map(({ user, terminal, first }) => {
                  const abnormal = statusLabel(user.status)
                  return (
                    <TableRow key={`${user.loginId}:${terminal.tokenTail}`}>
                      <TableCell>
                        {first ? (
                          <div className='flex items-center gap-3'>
                            <Avatar className='h-8 w-8'>
                              {user.avatar && <AvatarImage src={user.avatar} />}
                              <AvatarFallback className='text-xs'>
                                {(user.nickname || user.username || '?')
                                  .slice(0, 2)
                                  .toUpperCase()}
                              </AvatarFallback>
                            </Avatar>
                            <div className='min-w-0'>
                              <div className='flex items-center gap-1.5'>
                                <span className='truncate text-sm font-medium'>
                                  {user.nickname || user.username || user.loginId}
                                </span>
                                {abnormal && (
                                  <Badge variant='secondary' className='text-xs'>
                                    {abnormal}
                                  </Badge>
                                )}
                              </div>
                              <div className='truncate text-xs text-muted-foreground'>
                                {user.username}
                              </div>
                            </div>
                          </div>
                        ) : null}
                      </TableCell>
                      <TableCell>
                        <div className='flex items-center gap-1.5'>
                          <RiComputerLine className='size-4 shrink-0 text-muted-foreground' />
                          <span className='truncate text-sm'>
                            {deviceLabel(terminal)}
                          </span>
                          {terminal.current && (
                            <Badge className='shrink-0 text-xs'>
                              {t('loginManagement.currentSession')}
                            </Badge>
                          )}
                        </div>
                        <div className='mt-0.5 truncate text-xs text-muted-foreground'>
                          {[terminal.ip, `${t('loginManagement.tokenTail')} ${terminal.tokenTail}`]
                            .filter(Boolean)
                            .join(' · ')}
                        </div>
                      </TableCell>
                      <TableCell className='whitespace-nowrap text-xs text-muted-foreground'>
                        {terminal.loginTime
                          ? dayjs(terminal.loginTime).format('YYYY-MM-DD HH:mm')
                          : '—'}
                      </TableCell>
                      <TableCell className='whitespace-nowrap text-xs text-muted-foreground'>
                        {agoLabel(terminal.lastActiveTime)}
                      </TableCell>
                      <TableCell>
                        <div className='flex items-center gap-2'>
                          <Button
                            variant='ghost'
                            size='sm'
                            className='h-7 px-2.5 text-xs text-destructive hover:text-destructive'
                            disabled={!!actingKey}
                            onClick={() => setTarget({ type: 'terminal', user, terminal })}
                          >
                            {t('loginManagement.kickDevice')}
                          </Button>
                          {first && (
                            <Button
                              variant='outline'
                              size='sm'
                              className='h-7 px-2.5 text-xs'
                              disabled={!!actingKey}
                              onClick={() => setTarget({ type: 'user', user })}
                            >
                              {t('loginManagement.kickAll')}
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })
              )}
            </TableBody>
          </Table>
        </div>

        {/* 移动端：表格塞不下，按用户分组改为卡片 */}
        <div className='rounded-md border md:hidden'>
          {loading && users.length === 0 ? (
            <div className='py-8 text-center text-sm text-muted-foreground'>
              {t('loginManagement.loading')}
            </div>
          ) : users.length === 0 ? (
            <div className='py-8 text-center text-sm text-muted-foreground'>
              {t('loginManagement.empty')}
            </div>
          ) : (
            users.map((user) => {
              const abnormal = statusLabel(user.status)
              return (
                <div
                  key={user.loginId}
                  className='border-b p-4 last:border-b-0'
                >
                  <div className='flex items-start justify-between gap-2'>
                    <div className='flex min-w-0 items-center gap-3'>
                      <Avatar className='h-8 w-8'>
                        {user.avatar && <AvatarImage src={user.avatar} />}
                        <AvatarFallback className='text-xs'>
                          {(user.nickname || user.username || '?')
                            .slice(0, 2)
                            .toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                      <div className='min-w-0'>
                        <div className='flex items-center gap-1.5'>
                          <span className='truncate text-sm font-medium'>
                            {user.nickname || user.username || user.loginId}
                          </span>
                          {abnormal && (
                            <Badge variant='secondary' className='text-xs'>
                              {abnormal}
                            </Badge>
                          )}
                        </div>
                        <div className='truncate text-xs text-muted-foreground'>
                          {user.username}
                        </div>
                      </div>
                    </div>
                    <Button
                      variant='outline'
                      size='sm'
                      className='h-7 shrink-0 px-2.5 text-xs'
                      disabled={!!actingKey}
                      onClick={() => setTarget({ type: 'user', user })}
                    >
                      {t('loginManagement.kickAll')}
                    </Button>
                  </div>
                  <ul className='mt-3 space-y-2'>
                    {(user.terminals ?? []).map((terminal) => (
                      <li
                        key={terminal.tokenTail}
                        className='rounded-md bg-muted/40 p-3'
                      >
                        <div className='flex items-center justify-between gap-2'>
                          <div className='flex min-w-0 items-center gap-1.5'>
                            <RiComputerLine className='size-4 shrink-0 text-muted-foreground' />
                            <span className='truncate text-sm'>
                              {deviceLabel(terminal)}
                            </span>
                            {terminal.current && (
                              <Badge className='shrink-0 text-xs'>
                                {t('loginManagement.currentSession')}
                              </Badge>
                            )}
                          </div>
                          <Button
                            variant='ghost'
                            size='sm'
                            className='h-7 shrink-0 px-2.5 text-xs text-destructive hover:text-destructive'
                            disabled={!!actingKey}
                            onClick={() =>
                              setTarget({ type: 'terminal', user, terminal })
                            }
                          >
                            {t('loginManagement.kickDevice')}
                          </Button>
                        </div>
                        <div className='mt-1 truncate text-xs text-muted-foreground'>
                          {[
                            terminal.ip,
                            `${t('loginManagement.tokenTail')} ${terminal.tokenTail}`,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                        </div>
                        <div className='mt-1 flex flex-wrap gap-x-3 text-xs text-muted-foreground'>
                          <span>
                            {t('loginManagement.colLoginAt')}{' '}
                            {terminal.loginTime
                              ? dayjs(terminal.loginTime).format(
                                  'MM-DD HH:mm'
                                )
                              : '—'}
                          </span>
                          <span>
                            {t('loginManagement.colLastActive')}{' '}
                            {agoLabel(terminal.lastActiveTime)}
                          </span>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              )
            })
          )}
        </div>
      </div>

      <AlertDialog open={!!target} onOpenChange={(open) => !open && setTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{confirmTitle()}</AlertDialogTitle>
            <AlertDialogDescription>{confirmDesc()}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('account.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={handleKick}
            >
              {target?.type === 'user'
                ? t('loginManagement.kickAll')
                : t('loginManagement.kickDevice')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
