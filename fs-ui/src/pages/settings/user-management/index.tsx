import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import dayjs from 'dayjs'
import {
  ChevronLeft,
  ChevronRight,
  LockOpen,
  MoreHorizontal,
  RefreshCw,
  Search,
  UserPlus,
} from 'lucide-react'
import { adminApi, isHandledError } from '@/api'
import { useAuth } from '@/contexts/auth-context'
import type { AdminUser } from '@/types/user'
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
import { EditUserDialog } from './edit-user-dialog'
import { ResetPasswordDialog } from './reset-password-dialog'
import { DeleteUserDialog } from './delete-user-dialog'

const PAGE_SIZE = 10

interface QueryState {
  keyword?: string
  status?: number
  page: number
}

const formatTime = (value?: string) => {
  if (!value) return null
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : null
}

/** 用户管理：全部账号的增删改查、审核、启用/禁用、重置密码与强制下线 */
export function SettingsUserManagement() {
  const { t } = useTranslation('settings')
  const { user: currentUser } = useAuth()
  const [users, setUsers] = useState<AdminUser[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [query, setQuery] = useState<QueryState>({ page: 1 })
  const [keywordInput, setKeywordInput] = useState('')
  const [actingId, setActingId] = useState<string | null>(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<AdminUser | null>(null)
  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<AdminUser | null>(null)
  const [rejectTarget, setRejectTarget] = useState<AdminUser | null>(null)
  const [disableTarget, setDisableTarget] = useState<AdminUser | null>(null)
  const [kickTarget, setKickTarget] = useState<AdminUser | null>(null)

  const fetchUsers = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminApi.listUsers({
        keyword: query.keyword,
        status: query.status,
        page: query.page,
        pageSize: PAGE_SIZE,
      })
      setUsers(res?.records ?? [])
      setTotal(Number(res?.total ?? 0))
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.listFailed'))
    } finally {
      setLoading(false)
    }
  }, [query, t])

  useEffect(() => {
    fetchUsers()
  }, [fetchUsers])

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const submitSearch = () => {
    const keyword = keywordInput.trim()
    setQuery((prev) => ({ ...prev, keyword: keyword || undefined, page: 1 }))
  }

  const handleApprove = async (user: AdminUser) => {
    setActingId(user.id)
    try {
      await adminApi.approveUser(user.id)
      toast.success(t('userManagement.approved'))
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
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
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleUnlock = async (user: AdminUser) => {
    setActingId(user.id)
    try {
      await adminApi.unlockUser(user.username)
      toast.success(t('userManagement.unlocked'))
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleEnable = async (user: AdminUser) => {
    setActingId(user.id)
    try {
      await adminApi.setUserEnabled(user.id, true)
      toast.success(t('userManagement.enabled'))
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleDisable = async () => {
    if (!disableTarget) return
    setActingId(disableTarget.id)
    try {
      await adminApi.setUserEnabled(disableTarget.id, false)
      toast.success(t('userManagement.disabled'))
      setDisableTarget(null)
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleKick = async () => {
    if (!kickTarget) return
    setActingId(kickTarget.id)
    try {
      const count = await adminApi.kickoutUser(kickTarget.id)
      toast.success(
        t('userManagement.kickDone', {
          n: count ?? kickTarget.onlineTerminals,
        })
      )
      setKickTarget(null)
      fetchUsers()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleDeleted = (result: 'disabled' | 'deleted') => {
    // 删掉当前页最后一行时回退一页，避免停在空白页
    if (result === 'deleted' && users.length === 1 && query.page > 1) {
      setQuery((prev) => ({ ...prev, page: prev.page - 1 }))
      return
    }
    fetchUsers()
  }

  const statusBadge = (status: number) => {
    switch (status) {
      case 1:
        return { label: t('userManagement.statusDisabled'), variant: 'destructive' as const }
      case 2:
        return { label: t('userManagement.statusPending'), variant: 'outline' as const }
      case 3:
        return { label: t('userManagement.statusRejected'), variant: 'outline' as const }
      default:
        return { label: t('userManagement.statusNormal'), variant: 'secondary' as const }
    }
  }

  const lastLoginText = (user: AdminUser) =>
    formatTime(user.lastLoginAt) ?? t('userManagement.neverLoggedIn')

  const createdText = (user: AdminUser) => formatTime(user.createdAt) ?? '—'

  const renderActions = (user: AdminUser) => {
    const self = user.id === currentUser?.id
    const acting = actingId === user.id
    const pending = user.status === 2
    const rejected = user.status === 3
    const canToggle = !self && !user.superAdmin && (user.status === 0 || user.status === 1)
    const canDelete = !self && !user.superAdmin

    return (
      <div className='flex items-center justify-end gap-1'>
        {pending && (
          <>
            <Button
              size='sm'
              className='h-7 px-2.5 text-xs'
              disabled={acting}
              onClick={() => handleApprove(user)}
            >
              {t('userManagement.approve')}
            </Button>
            <Button
              variant='ghost'
              size='sm'
              className='h-7 px-2.5 text-xs text-destructive hover:text-destructive'
              disabled={acting}
              onClick={() => setRejectTarget(user)}
            >
              {t('userManagement.reject')}
            </Button>
          </>
        )}
        {user.locked && (
          <Button
            variant='ghost'
            size='sm'
            className='h-7 gap-1 px-2.5 text-xs'
            disabled={acting}
            onClick={() => handleUnlock(user)}
          >
            <LockOpen className='h-3.5 w-3.5' />
            {t('userManagement.unlock')}
          </Button>
        )}
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              variant='ghost'
              size='icon'
              className='size-7'
              disabled={acting}
              aria-label={t('userManagement.actions')}
            >
              <MoreHorizontal className='h-4 w-4' />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align='end' className='w-40'>
            <DropdownMenuItem onClick={() => setEditTarget(user)}>
              {t('userManagement.editUser')}
            </DropdownMenuItem>
            {!self && !rejected && (
              <DropdownMenuItem onClick={() => setResetTarget(user)}>
                {t('userManagement.resetPassword')}
              </DropdownMenuItem>
            )}
            {canToggle && !user.locked && (
              <DropdownMenuItem
                onClick={() =>
                  user.status === 0 ? setDisableTarget(user) : handleEnable(user)
                }
              >
                {user.status === 0
                  ? t('userManagement.disable')
                  : t('userManagement.enable')}
              </DropdownMenuItem>
            )}
            {!self && user.onlineTerminals > 0 && (
              <DropdownMenuItem onClick={() => setKickTarget(user)}>
                {t('userManagement.kickOnline')}
              </DropdownMenuItem>
            )}
            {canDelete && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  className='text-destructive focus:text-destructive'
                  onClick={() => setDeleteTarget(user)}
                >
                  {t('userManagement.deleteUser')}
                </DropdownMenuItem>
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    )
  }

  const busy = loading || actingId !== null

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex flex-wrap items-start justify-between gap-4'>
        <div>
          <SettingsPageTitle>{t('userManagement.pageTitle')}</SettingsPageTitle>
          <SettingsPageDescription>
            {t('userManagement.pageDescription')}
          </SettingsPageDescription>
        </div>
        <div className='flex shrink-0 items-center gap-1'>
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
            onClick={fetchUsers}
            disabled={loading}
            aria-label={t('userManagement.refresh')}
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          </Button>
        </div>
      </header>

      <div className='mt-4 flex flex-wrap items-center gap-2'>
        <form
          className='relative min-w-0 flex-1 sm:max-w-64'
          onSubmit={(e) => {
            e.preventDefault()
            submitSearch()
          }}
        >
          <button
            type='submit'
            aria-label={t('userManagement.search')}
            className='absolute top-1/2 left-1.5 flex size-6 -translate-y-1/2 items-center justify-center rounded text-muted-foreground transition-colors hover:text-foreground'
          >
            <Search className='size-4' />
          </button>
          <Input
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            placeholder={t('userManagement.searchPlaceholder')}
            enterKeyHint='search'
            className='h-8 pl-8 text-sm'
          />
        </form>
        <Select
          value={query.status === undefined ? 'all' : String(query.status)}
          onValueChange={(value) =>
            setQuery((prev) => ({
              ...prev,
              status: value === 'all' ? undefined : Number(value),
              page: 1,
            }))
          }
        >
          <SelectTrigger size='sm' className='w-32 text-xs'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value='all'>{t('userManagement.filterAll')}</SelectItem>
            <SelectItem value='0'>{t('userManagement.statusNormal')}</SelectItem>
            <SelectItem value='1'>{t('userManagement.statusDisabled')}</SelectItem>
            <SelectItem value='2'>{t('userManagement.statusPending')}</SelectItem>
            <SelectItem value='3'>{t('userManagement.statusRejected')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className='mt-4 flex-1'>
        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('userManagement.colUser')}</TableHead>
                <TableHead className='hidden md:table-cell'>
                  {t('userManagement.colStatus')}
                </TableHead>
                <TableHead className='hidden whitespace-nowrap md:table-cell'>
                  {t('userManagement.colLastLogin')}
                </TableHead>
                <TableHead className='hidden whitespace-nowrap lg:table-cell'>
                  {t('userManagement.colCreatedAt')}
                </TableHead>
                <TableHead className='w-24'>
                  {t('userManagement.colActions')}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && users.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userManagement.loading')}
                  </TableCell>
                </TableRow>
              ) : users.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={5}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userManagement.empty')}
                  </TableCell>
                </TableRow>
              ) : (
                users.map((user) => {
                  const self = user.id === currentUser?.id
                  const status = statusBadge(user.status)
                  const name = user.nickname || user.username
                  return (
                    <TableRow key={user.id}>
                      <TableCell>
                        <div className='flex items-center gap-3'>
                          <Avatar className='h-8 w-8'>
                            {user.avatar && <AvatarImage src={user.avatar} />}
                            <AvatarFallback className='text-xs'>
                              {name.slice(0, 2).toUpperCase()}
                            </AvatarFallback>
                          </Avatar>
                          <div className='min-w-0'>
                            <div className='flex flex-wrap items-center gap-x-2 gap-y-1'>
                              <span className='truncate text-sm font-medium'>
                                {name}
                              </span>
                              {user.superAdmin && (
                                <Badge
                                  variant='secondary'
                                  className='h-5 shrink-0 px-1.5 text-[10px] font-normal'
                                >
                                  {t('userManagement.badgeSuperAdmin')}
                                </Badge>
                              )}
                              {self && (
                                <Badge
                                  variant='outline'
                                  className='h-5 shrink-0 px-1.5 text-[10px] font-normal'
                                >
                                  {t('userManagement.badgeSelf')}
                                </Badge>
                              )}
                              {user.locked && (
                                <Badge
                                  variant='destructive'
                                  className='h-5 shrink-0 px-1.5 text-[10px] font-normal'
                                >
                                  {t('userManagement.badgeLocked')}
                                </Badge>
                              )}
                              {user.onlineTerminals > 0 && (
                                <Badge className='h-5 shrink-0 px-1.5 text-[10px] font-normal'>
                                  {t('userManagement.badgeOnline', {
                                    n: user.onlineTerminals,
                                  })}
                                </Badge>
                              )}
                            </div>
                            <div className='truncate text-xs text-muted-foreground'>
                              {user.username}
                            </div>
                            {/* 窄屏把被折叠的时间列放到用户名下方 */}
                            <div className='truncate text-[11px] text-muted-foreground md:hidden'>
                              {t('userManagement.colLastLogin')}：
                              {lastLoginText(user)}
                            </div>
                            <div className='truncate text-[11px] text-muted-foreground lg:hidden'>
                              {t('userManagement.colCreatedAt')}：{createdText(user)}
                            </div>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className='hidden md:table-cell'>
                        <Badge
                          variant={status.variant}
                          className='text-[11px] font-normal'
                        >
                          {status.label}
                        </Badge>
                      </TableCell>
                      <TableCell className='hidden text-xs whitespace-nowrap text-muted-foreground md:table-cell'>
                        {lastLoginText(user)}
                      </TableCell>
                      <TableCell className='hidden text-xs whitespace-nowrap text-muted-foreground lg:table-cell'>
                        {createdText(user)}
                      </TableCell>
                      <TableCell>{renderActions(user)}</TableCell>
                    </TableRow>
                  )
                })
              )}
            </TableBody>
          </Table>
        </div>

        <div className='mt-3 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground'>
          <span>
            {t('userManagement.pagination', {
              page: query.page,
              pages,
              total,
            })}
          </span>
          <div className='flex items-center gap-1'>
            <Button
              variant='ghost'
              size='sm'
              className='h-7 gap-0.5 px-2 text-xs'
              disabled={busy || query.page <= 1}
              onClick={() =>
                setQuery((prev) => ({ ...prev, page: Math.max(1, prev.page - 1) }))
              }
            >
              <ChevronLeft className='h-3.5 w-3.5' />
              {t('userManagement.prevPage')}
            </Button>
            <Button
              variant='ghost'
              size='sm'
              className='h-7 gap-0.5 px-2 text-xs'
              disabled={busy || query.page >= pages}
              onClick={() =>
                setQuery((prev) => ({
                  ...prev,
                  page: Math.min(pages, prev.page + 1),
                }))
              }
            >
              {t('userManagement.nextPage')}
              <ChevronRight className='h-3.5 w-3.5' />
            </Button>
          </div>
        </div>
      </div>

      <CreateUserDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={fetchUsers}
      />

      <EditUserDialog
        user={editTarget}
        onClose={() => setEditTarget(null)}
        onSaved={fetchUsers}
      />

      <ResetPasswordDialog
        user={resetTarget}
        onClose={() => setResetTarget(null)}
        onDone={fetchUsers}
      />

      <DeleteUserDialog
        user={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onDone={handleDeleted}
      />

      <AlertDialog
        open={!!rejectTarget}
        onOpenChange={(open) => !open && setRejectTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('userManagement.confirmRejectTitle')}
            </AlertDialogTitle>
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

      <AlertDialog
        open={!!disableTarget}
        onOpenChange={(open) => !open && setDisableTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('userManagement.disable')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('userManagement.confirmDisableDesc', {
                name: disableTarget?.nickname || disableTarget?.username || '',
              })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('account.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={handleDisable}
            >
              {t('userManagement.disable')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={!!kickTarget}
        onOpenChange={(open) => !open && setKickTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('userManagement.confirmKickTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {t('userManagement.confirmKickDesc', {
                name: kickTarget?.nickname || kickTarget?.username || '',
                n: kickTarget?.onlineTerminals ?? 0,
              })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('account.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={handleKick}
            >
              {t('userManagement.kickOnline')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
