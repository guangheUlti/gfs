import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { RefreshCw } from 'lucide-react'
import dayjs from 'dayjs'
import { adminApi } from '@/api'
import type { PendingUser } from '@/types/user'
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'
import { Button } from '@/components/ui/button'
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

export function SettingsUserApproval() {
  const { t } = useTranslation('settings')
  const [users, setUsers] = useState<PendingUser[]>([])
  const [loading, setLoading] = useState(false)
  const [actingId, setActingId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<PendingUser | null>(null)

  const fetchPending = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminApi.listPendingUsers()
      setUsers(data ?? [])
    } catch (err: any) {
      if (!err?.handled) toast.error(t('userApproval.listFailed'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    fetchPending()
  }, [fetchPending])

  const handleApprove = async (user: PendingUser) => {
    setActingId(user.id)
    try {
      await adminApi.approveUser(user.id)
      toast.success(t('userApproval.approved'))
      fetchPending()
    } catch (err: any) {
      if (!err?.handled) toast.error(t('userApproval.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  const handleReject = async () => {
    if (!rejectTarget) return
    setActingId(rejectTarget.id)
    try {
      await adminApi.rejectUser(rejectTarget.id)
      toast.success(t('userApproval.rejected'))
      setRejectTarget(null)
      fetchPending()
    } catch (err: any) {
      if (!err?.handled) toast.error(t('userApproval.actionFailed'))
    } finally {
      setActingId(null)
    }
  }

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex items-start justify-between'>
        <div>
          <SettingsPageTitle>{t('userApproval.pageTitle')}</SettingsPageTitle>
          <SettingsPageDescription>
            {t('userApproval.pageDescription')}
          </SettingsPageDescription>
        </div>
        <Button
          variant='ghost'
          size='icon'
          className='size-8'
          onClick={fetchPending}
          disabled={loading}
          aria-label={t('userApproval.refresh')}
        >
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        </Button>
      </header>

      <div className='mt-8 flex-1'>
        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('userApproval.colUser')}</TableHead>
                <TableHead>{t('userApproval.colRegisteredAt')}</TableHead>
                <TableHead className='w-40'>{t('userApproval.colActions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && users.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={3}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userApproval.loading')}
                  </TableCell>
                </TableRow>
              ) : users.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={3}
                    className='py-8 text-center text-muted-foreground'
                  >
                    {t('userApproval.empty')}
                  </TableCell>
                </TableRow>
              ) : (
                users.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className='flex items-center gap-3'>
                        <Avatar className='h-8 w-8'>
                          {u.avatar && <AvatarImage src={u.avatar} />}
                          <AvatarFallback className='text-xs'>
                            {(u.nickname || u.username).slice(0, 2).toUpperCase()}
                          </AvatarFallback>
                        </Avatar>
                        <div className='min-w-0'>
                          <div className='truncate text-sm font-medium'>
                            {u.nickname || u.username}
                          </div>
                          <div className='truncate text-xs text-muted-foreground'>
                            {u.username}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className='text-xs text-muted-foreground'>
                      {u.createdAt
                        ? dayjs(u.createdAt).format('YYYY-MM-DD HH:mm')
                        : '—'}
                    </TableCell>
                    <TableCell>
                      <div className='flex items-center gap-2'>
                        <Button
                          size='sm'
                          className='h-7 px-2.5 text-xs'
                          disabled={actingId === u.id}
                          onClick={() => handleApprove(u)}
                        >
                          {t('userApproval.approve')}
                        </Button>
                        <Button
                          variant='ghost'
                          size='sm'
                          className='h-7 px-2.5 text-xs text-destructive hover:text-destructive'
                          disabled={actingId === u.id}
                          onClick={() => setRejectTarget(u)}
                        >
                          {t('userApproval.reject')}
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </div>

      <AlertDialog
        open={!!rejectTarget}
        onOpenChange={(open) => !open && setRejectTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('userApproval.confirmRejectTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('userApproval.confirmRejectDesc', {
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
              {t('userApproval.reject')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
