import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { LockOpen, RefreshCw, UserPlus } from 'lucide-react'
import dayjs from 'dayjs'
import { adminApi } from '@/api'
import type { LockedUser, PendingUser } from '@/types/user'
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'
import { Button } from '@/components/ui/button'
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
  TableHead,
  TableHeader,
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
import { CreateUserDialog } from './create-user-dialog'

/** 表格行：待审核用户与被防暴力锁定的账号共用一张表 */
type Row =
  | { kind: 'pending'; key: string; user: PendingUser }
  | { kind: 'locked'; key: string; user: LockedUser }

/** 用户管理：手动创建用户 + 审核新注册用户 + 解除锁定账号 */
export function SettingsUserManagement() {
  const { t } = useTranslation('settings')
  const [pending, setPending] = useState<PendingUser[]>([])
  const [locked, setLocked] = useState<LockedUser[]>([])
  const [loading, setLoading] = useState(false)
  const [actingId, setActingId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<PendingUser | null>(null)
  const [createOpen, setCreateOpen] = useState(false)

  const fetchAll = useCallback(async () => {
    setLoading(true)
    try {
      const [pendingList, lockedList] = await Promise.all([
        adminApi.listPendingUsers(),
        adminApi.listLockedUsers(),
      ])
      setPending(pendingList ?? [])
      setLocked(lockedList ?? [])
    } catch (err) {
      if (!err?.handled) toast.error(t('userManagement.listFailed'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    fetchAll()
  }, [fetchAll])

  const handleApprove = async (user: PendingUser) => {
    setActingId(user.id)
    try {
      await adminApi.approveUser(user.id)
      toast.success(t('userManagement.approved'))
      fetchAll()
    } catch (err) {
      if (!err?.handled) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleReject = async () => {
    if (!rejectTarget) return
    setActingId(rejectTarget.id)
    try {
      await adminApi.rejectUser(rejectTarget.id)
      toast.success(t('userManagement.rejected'))
      setRejectTarget(null)
      fetchAll()
    } catch (err) {
      if (!err?.handled) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleUnlock = async (user: LockedUser) => {
    setActingId(`locked:${user.username}`)
    try {
      await adminApi.unlockUser(user.username)
      toast.success(t('userManagement.unlocked'))
      fetchAll()
    } catch (err) {
      if (!err?.handled) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  // 待审核在前、锁定在后：两类都待管理员处理
  const rows: Row[] = [
    ...pending.map<Row>((user) => ({
      kind: 'pending',
      key: `pending:${user.id}`,
      user,
    })),
    ...locked.map<Row>((user) => ({
      kind: 'locked',
      key: `locked:${user.username}`,
      user,
    })),
  ]

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex items-start justify-between'>
        <div>
          <SettingsPageTitle>{t('userManagement.pageTitle')}</SettingsPageTitle>
          <SettingsPageDescription>
            {t('userManagement.pageDescription')}
          </SettingsPageDescription>
        </div>
        <div className='flex items-center gap-1'>
          <Button
            variant='ghost'
            size='sm'
            className='h-8 gap-1.5 px-2.5 text-xs'
            onClick={() => setCreateOpen(true)}
          >
            <UserPlus className='h-4 w-4' />
            {t('userManagement.createUser')}
          </Button>
          <Button
            variant='ghost'
            size='icon'
            className='size-8'
            onClick={fetchAll}
            disabled={loading}
            aria-label={t('userManagement.refresh')}
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          </Button>
        </div>
      </header>

      <div className='mt-8 flex-1'>
        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('userManagement.colUser')}</TableHead>
                {/* 手机（<sm）把时间折叠到用户名下方，只留 用户 + 操作 两列，避免表格横向滚动 */}
                <TableHead className='hidden sm:table-cell'>
                  {t('userManagement.colTime')}
                </TableHead>
                <TableHead className='w-28 sm:w-40'>
                  {t('userManagement.colActions')}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && rows.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={3}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userManagement.loading')}
                  </TableCell>
                </TableRow>
              ) : rows.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={3}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userManagement.empty')}
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((row) => {
                  const name = row.user.nickname || row.user.username
                  const timeText =
                    row.kind === 'pending'
                      ? row.user.createdAt
                        ? dayjs(row.user.createdAt).format('YYYY-MM-DD HH:mm')
                        : '—'
                      : t('userManagement.lockedRemaining', {
                          minutes: Math.max(
                            1,
                            Math.ceil(row.user.remainingSeconds / 60)
                          ),
                        })
                  const actingKey =
                    row.kind === 'pending' ? row.user.id : `locked:${row.user.username}`
                  return (
                    <TableRow key={row.key}>
                      <TableCell>
                        <div className='flex items-center gap-3'>
                          <Avatar className='h-8 w-8'>
                            {row.user.avatar && (
                              <AvatarImage src={row.user.avatar} />
                            )}
                            <AvatarFallback className='text-xs'>
                              {name.slice(0, 2).toUpperCase()}
                            </AvatarFallback>
                          </Avatar>
                          <div className='min-w-0'>
                            <div className='flex items-center gap-2'>
                              <div className='truncate text-sm font-medium'>
                                {name}
                              </div>
                              <Badge
                                variant={
                                  row.kind === 'pending'
                                    ? 'secondary'
                                    : 'destructive'
                                }
                                className='h-5 shrink-0 px-1.5 text-[10px] font-normal'
                              >
                                {row.kind === 'pending'
                                  ? t('userManagement.badgePending')
                                  : t('userManagement.badgeLocked')}
                              </Badge>
                            </div>
                            <div className='truncate text-xs text-muted-foreground'>
                              {row.user.username}
                            </div>
                            {/* 手机专享：被折叠掉的时间列内容放到用户名下方 */}
                            <div className='truncate text-[11px] text-muted-foreground sm:hidden'>
                              {timeText}
                            </div>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className='hidden text-xs text-muted-foreground sm:table-cell'>
                        {timeText}
                      </TableCell>
                      <TableCell>
                        {row.kind === 'pending' ? (
                          <div className='flex items-center gap-2'>
                            <Button
                              size='sm'
                              className='h-7 px-2.5 text-xs'
                              disabled={actingId === actingKey}
                              onClick={() => handleApprove(row.user)}
                            >
                              {t('userManagement.approve')}
                            </Button>
                            <Button
                              variant='ghost'
                              size='sm'
                              className='h-7 px-2.5 text-xs text-destructive hover:text-destructive'
                              disabled={actingId === actingKey}
                              onClick={() => setRejectTarget(row.user)}
                            >
                              {t('userManagement.reject')}
                            </Button>
                          </div>
                        ) : (
                          <Button
                            variant='ghost'
                            size='sm'
                            className='h-7 gap-1 px-2.5 text-xs'
                            disabled={actingId === actingKey}
                            onClick={() => handleUnlock(row.user)}
                          >
                            <LockOpen className='h-3.5 w-3.5' />
                            {t('userManagement.unlock')}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  )
                })
              )}
            </TableBody>
          </Table>
        </div>
      </div>

      <CreateUserDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={fetchAll}
      />

      <AlertDialog
        open={!!rejectTarget}
        onOpenChange={(open) => !open && setRejectTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('userManagement.confirmRejectTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('userManagement.confirmRejectDesc', {
                name: rejectTarget?.nickname || rejectTarget?.username || '',
              })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('account.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={handleReject}
            >
              {t('userManagement.reject')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
