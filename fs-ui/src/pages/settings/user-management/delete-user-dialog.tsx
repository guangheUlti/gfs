import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { adminApi, isHandledError } from '@/api'
import type { AdminUser, AdminUserDataSummary } from '@/types/user'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

interface DeleteUserDialogProps {
  user: AdminUser | null
  onClose: () => void
  onDone: (result: 'disabled' | 'deleted') => void
}

type Mode = 'disable' | 'purge'

interface OptionCardProps {
  selected: boolean
  destructive?: boolean
  title: string
  hint: string
  disabled?: boolean
  onSelect: () => void
}

function OptionCard({
  selected,
  destructive,
  title,
  hint,
  disabled,
  onSelect,
}: OptionCardProps) {
  const accent = destructive ? 'border-destructive' : 'border-primary'
  return (
    <button
      type='button'
      role='radio'
      aria-checked={selected}
      disabled={disabled}
      onClick={onSelect}
      className={cn(
        'w-full rounded-md border p-3 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-60',
        selected
          ? cn(accent, destructive ? 'bg-destructive/5' : 'bg-primary/5')
          : 'hover:bg-muted/60'
      )}
    >
      <span className='flex items-center gap-2 text-sm font-medium'>
        <span
          className={cn(
            'flex size-4 shrink-0 items-center justify-center rounded-full border',
            selected ? accent : 'border-muted-foreground/40'
          )}
        >
          {selected && (
            <span
              className={cn(
                'size-2 rounded-full',
                destructive ? 'bg-destructive' : 'bg-primary'
              )}
            />
          )}
        </span>
        {title}
      </span>
      <span className='mt-1 block pl-6 text-xs text-muted-foreground'>
        {hint}
      </span>
    </button>
  )
}

/** 删除账号：先查残留数据，有数据时让管理员在“仅禁用”和“彻底清除”之间选择 */
export function DeleteUserDialog({
  user,
  onClose,
  onDone,
}: DeleteUserDialogProps) {
  const { t } = useTranslation('settings')
  const [summary, setSummary] = useState<AdminUserDataSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadFailed, setLoadFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [mode, setMode] = useState<Mode | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // 待审核/已拒绝账号不能走“禁用”，只能删除
  const canDisable = user?.status === 0 || user?.status === 1
  const userId = user?.id ?? null

  useEffect(() => {
    if (!userId) return
    let cancelled = false
    setLoading(true)
    setLoadFailed(false)
    setSummary(null)
    setMode(null)
    setSubmitting(false)
    adminApi
      .getUserDataSummary(userId)
      .then((data) => {
        if (cancelled) return
        setSummary(data)
        if (!data?.hasData || !canDisable) setMode('purge')
      })
      .catch((err) => {
        if (cancelled) return
        setLoadFailed(true)
        if (!isHandledError(err)) toast.error(t('userManagement.loadDataFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [userId, canDisable, attempt, t])

  const name = user?.nickname || user?.username || ''

  const handleConfirm = async () => {
    if (!user || !mode || submitting) return
    setSubmitting(true)
    try {
      if (mode === 'disable') {
        await adminApi.setUserEnabled(user.id, false)
        toast.success(t('userManagement.disabled'))
        onClose()
        onDone('disabled')
      } else {
        await adminApi.deleteUser(user.id)
        toast.success(t('userManagement.deleted', { name }))
        onClose()
        onDone('deleted')
      }
    } catch (err) {
      if (!isHandledError(err)) {
        toast.error(
          mode === 'disable'
            ? t('userManagement.actionFailed')
            : t('userManagement.deleteFailed')
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  const residualRows = summary
    ? [
        summary.fileCount > 0 && (
          <li key='files'>
            {t('userManagement.residualFiles', {
              n: summary.fileCount,
              m: summary.recycleCount,
            })}
          </li>
        ),
        summary.favoriteCount > 0 && (
          <li key='favorites'>
            {t('userManagement.residualFavorites', { n: summary.favoriteCount })}
          </li>
        ),
        summary.shareCount > 0 && (
          <li key='shares'>
            {t('userManagement.residualShares', { n: summary.shareCount })}
          </li>
        ),
        summary.collectionCount > 0 && (
          <li key='collections'>
            {t('userManagement.residualCollections', {
              n: summary.collectionCount,
            })}
          </li>
        ),
        summary.transferTaskCount > 0 && (
          <li key='tasks'>
            {t('userManagement.residualTasks', { n: summary.transferTaskCount })}
          </li>
        ),
        summary.ossKeyCount > 0 && (
          <li key='oss'>
            {t('userManagement.residualOss', { n: summary.ossKeyCount })}
          </li>
        ),
      ].filter(Boolean)
    : []

  const needsChoice = !!summary?.hasData && canDisable
  const confirmLabel = submitting
    ? t('userManagement.deleting')
    : mode === 'disable'
      ? t('userManagement.disable')
      : needsChoice
        ? t('userManagement.deleteConfirm')
        : t('userManagement.deleteUser')

  return (
    <Dialog
      open={!!user}
      onOpenChange={(open) => !open && !submitting && onClose()}
    >
      <DialogContent className='sm:max-w-[480px]'>
        <DialogHeader>
          <DialogTitle>{t('userManagement.deleteTitle', { name })}</DialogTitle>
          <DialogDescription>{user?.username}</DialogDescription>
        </DialogHeader>

        {loading ? (
          <p className='py-6 text-center text-sm text-muted-foreground'>
            {t('userManagement.deleteChecking')}
          </p>
        ) : loadFailed ? (
          <div className='space-y-3 py-2'>
            <p className='text-sm text-destructive'>
              {t('userManagement.loadDataFailed')}
            </p>
            <Button
              variant='outline'
              size='sm'
              onClick={() => setAttempt((n) => n + 1)}
            >
              {t('userManagement.refresh')}
            </Button>
          </div>
        ) : summary ? (
          <div className='space-y-4'>
            {!summary.hasData ? (
              <p className='text-sm text-muted-foreground'>
                {t('userManagement.deleteNoData')}
              </p>
            ) : (
              <>
                <div className='space-y-2'>
                  <p className='text-sm'>{t('userManagement.residualIntro')}</p>
                  <ul className='space-y-1 rounded-md border bg-muted/40 p-3 text-sm'>
                    {residualRows}
                  </ul>
                </div>
                <div
                  role='radiogroup'
                  aria-label={t('userManagement.residualIntro')}
                  className='space-y-2'
                >
                  {canDisable && (
                    <OptionCard
                      selected={mode === 'disable'}
                      title={t('userManagement.deleteDisableOnly')}
                      hint={t('userManagement.deleteDisableOnlyHint')}
                      disabled={submitting}
                      onSelect={() => setMode('disable')}
                    />
                  )}
                  <OptionCard
                    selected={mode === 'purge'}
                    destructive
                    title={t('userManagement.deletePurge')}
                    hint={t('userManagement.deletePurgeHint')}
                    disabled={submitting}
                    onSelect={() => setMode('purge')}
                  />
                </div>
              </>
            )}
          </div>
        ) : null}

        <DialogFooter>
          <Button variant='outline' onClick={onClose} disabled={submitting}>
            {t('userManagement.cancel')}
          </Button>
          <Button
            variant={mode === 'disable' ? 'default' : 'destructive'}
            onClick={handleConfirm}
            disabled={!mode || submitting || loading || loadFailed}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
