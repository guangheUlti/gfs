import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { AlertTriangle } from 'lucide-react'
import { adminApi, isHandledError } from '@/api'
import type { AdminBatchDeleteResult, AdminUser } from '@/types/user'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

interface BatchDeleteUsersDialogProps {
  /** 待删除的账号；为空时不展示弹窗 */
  users: AdminUser[]
  onClose: () => void
  /** 关闭后刷新列表（有账号被删除时才调用） */
  onDone: () => void
}

/**
 * 批量删除账号：逐个删除并彻底清除各自数据。
 * 与单个删除不同，这里不给「仅禁用」选项——批量场景下禁用可以逐个操作，这里只做不可恢复的清除。
 */
export function BatchDeleteUsersDialog({
  users,
  onClose,
  onDone,
}: BatchDeleteUsersDialogProps) {
  const { t } = useTranslation('settings')
  const [acknowledged, setAcknowledged] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<AdminBatchDeleteResult | null>(null)

  const open = users.length > 0

  useEffect(() => {
    if (!open) return
    setAcknowledged(false)
    setSubmitting(false)
    setResult(null)
  }, [open])

  const handleConfirm = async () => {
    if (!open || !acknowledged || submitting) return
    setSubmitting(true)
    try {
      const res = await adminApi.deleteUsers(users.map((user) => user.id))
      const deleted = res?.deletedCount ?? 0
      const failed = res?.failed ?? []
      if (deleted > 0) {
        toast.success(t('userManagement.batchDeleted', { n: deleted }))
      }
      if (failed.length > 0) {
        setResult({ deletedCount: deleted, failed })
        return
      }
      onClose()
      onDone()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.deleteFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  const nameOf = (user: AdminUser) => user.nickname || user.username

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => !next && !submitting && onClose()}
    >
      <DialogContent className='sm:max-w-[480px]'>
        <DialogHeader>
          <DialogTitle>
            {t('userManagement.batchDeleteTitle', { n: users.length })}
          </DialogTitle>
          <DialogDescription>{t('userManagement.batchDeleteDesc')}</DialogDescription>
        </DialogHeader>

        {result ? (
          <div className='space-y-3'>
            <p className='text-sm'>
              {t('userManagement.batchDeletePartial', {
                done: result.deletedCount,
                failed: result.failed.length,
              })}
            </p>
            <ul className='max-h-56 space-y-1 overflow-auto rounded-md border bg-muted/40 p-3 text-sm'>
              {result.failed.map((item) => (
                <li key={item.id} className='break-all'>
                  <span className='font-medium'>{item.username || item.id}</span>
                  {item.reason ? (
                    <span className='text-muted-foreground'>：{item.reason}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <div className='space-y-3'>
            <ul className='max-h-56 space-y-1 overflow-auto rounded-md border bg-muted/40 p-3 text-sm'>
              {users.map((user) => (
                <li key={user.id} className='break-all'>
                  <span className='font-medium'>{nameOf(user)}</span>
                  <span className='text-muted-foreground'>（{user.username}）</span>
                </li>
              ))}
            </ul>
            <div className='flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-xs text-destructive'>
              <AlertTriangle className='mt-0.5 size-4 shrink-0' />
              <span>{t('userManagement.batchDeleteWarning')}</span>
            </div>
            <Label
              htmlFor='batch-delete-ack'
              className='flex cursor-pointer items-center gap-2 text-xs font-normal text-muted-foreground'
            >
              <Checkbox
                id='batch-delete-ack'
                checked={acknowledged}
                disabled={submitting}
                onCheckedChange={(checked) => setAcknowledged(checked === true)}
              />
              {t('userManagement.batchDeleteAcknowledge')}
            </Label>
          </div>
        )}

        <DialogFooter>
          {result ? (
            <Button
              onClick={() => {
                onClose()
                onDone()
              }}
            >
              {t('userManagement.cancel')}
            </Button>
          ) : (
            <>
              <Button variant='outline' onClick={onClose} disabled={submitting}>
                {t('userManagement.cancel')}
              </Button>
              <Button
                variant='destructive'
                onClick={handleConfirm}
                disabled={!acknowledged || submitting}
              >
                {submitting
                  ? t('userManagement.deleting')
                  : t('userManagement.batchDeleteConfirm', { n: users.length })}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
