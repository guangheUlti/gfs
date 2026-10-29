import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { adminApi, isHandledError } from '@/api'
import type { AdminUser } from '@/types/user'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { PASSWORD_MIN_LENGTH } from './create-user-dialog'

interface ResetPasswordDialogProps {
  user: AdminUser | null
  onClose: () => void
  onDone: () => void
}

/** 管理员重置密码：保存后该账号全部会话立即下线 */
export function ResetPasswordDialog({
  user,
  onClose,
  onDone,
}: ResetPasswordDialogProps) {
  const { t } = useTranslation('settings')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!user) {
      setPassword('')
      setConfirmPassword('')
      setSubmitting(false)
    }
  }, [user])

  const canSubmit =
    password.length >= PASSWORD_MIN_LENGTH &&
    password === confirmPassword &&
    !submitting

  const handleReset = async () => {
    if (!user || !canSubmit) return
    setSubmitting(true)
    try {
      await adminApi.resetUserPassword(user.id, password)
      toast.success(t('userManagement.resetDone'))
      onClose()
      onDone()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  const name = user?.nickname || user?.username || ''

  return (
    <Dialog open={!!user} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className='sm:max-w-[420px]'>
        <DialogHeader>
          <DialogTitle>{t('userManagement.resetTitle')}</DialogTitle>
          <DialogDescription>
            {t('userManagement.resetDescription', { name })}
          </DialogDescription>
        </DialogHeader>

        <div className='space-y-4'>
          <div className='space-y-1.5'>
            <label htmlFor='reset-user-password' className='text-sm'>
              {t('userManagement.newPassword')}
            </label>
            <Input
              id='reset-user-password'
              type='password'
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={PASSWORD_MIN_LENGTH}
              autoFocus
              disabled={submitting}
            />
          </div>
          <div className='space-y-1.5'>
            <label htmlFor='reset-user-confirm' className='text-sm'>
              {t('userManagement.colConfirmPassword')}
            </label>
            <Input
              id='reset-user-confirm'
              type='password'
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              minLength={PASSWORD_MIN_LENGTH}
              disabled={submitting}
              onKeyDown={(e) => e.key === 'Enter' && handleReset()}
            />
            {confirmPassword.length > 0 && confirmPassword !== password && (
              <p className='text-xs text-destructive'>
                {t('userManagement.passwordMismatch')}
              </p>
            )}
            {confirmPassword.length > 0 &&
              confirmPassword === password &&
              password.length < PASSWORD_MIN_LENGTH && (
                <p className='text-xs text-destructive'>
                  {t('userManagement.passwordTooShort')}
                </p>
              )}
          </div>
        </div>

        <DialogFooter>
          <Button variant='outline' onClick={onClose} disabled={submitting}>
            {t('userManagement.cancel')}
          </Button>
          <Button onClick={handleReset} disabled={!canSubmit}>
            {submitting
              ? t('userManagement.resetting')
              : t('userManagement.reset')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
