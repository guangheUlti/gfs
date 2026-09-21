import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { adminApi } from '@/api'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

/** 密码最短长度：与后端 UserRegisterCmd / AdminUserCreateCmd 保持一致 */
export const PASSWORD_MIN_LENGTH = 6

interface CreateUserDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 创建成功回调（刷新列表用） */
  onCreated: () => void
}

/**
 * 管理员手动创建用户对话框：填写用户名/密码（昵称可选），
 * 创建后的账号为正常状态，无需再走注册审核即可登录。
 */
export function CreateUserDialog({
  open,
  onOpenChange,
  onCreated,
}: CreateUserDialogProps) {
  const { t } = useTranslation('settings')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [submitting, setSubmitting] = useState(false)

  // 关闭时重置表单，避免下次打开残留上次输入
  useEffect(() => {
    if (!open) {
      setUsername('')
      setPassword('')
      setConfirmPassword('')
      setNickname('')
      setSubmitting(false)
    }
  }, [open])

  const canSubmit =
    username.trim().length > 0 &&
    password.length >= PASSWORD_MIN_LENGTH &&
    password === confirmPassword &&
    !submitting

  const handleCreate = async () => {
    if (!canSubmit) return
    setSubmitting(true)
    try {
      await adminApi.createUser({
        username: username.trim(),
        password,
        confirmPassword,
        nickname: nickname.trim() || undefined,
      })
      toast.success(
        t('userManagement.created', { name: username.trim() })
      )
      onOpenChange(false)
      onCreated()
    } catch (err) {
      if (!err?.handled) toast.error(t('userManagement.createFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='sm:max-w-[420px]'>
        <DialogHeader>
          <DialogTitle>{t('userManagement.createTitle')}</DialogTitle>
          <DialogDescription>
            {t('userManagement.createDescription')}
          </DialogDescription>
        </DialogHeader>

        <div className='space-y-4'>
          <div className='space-y-1.5'>
            <label htmlFor='create-user-username' className='text-sm'>
              {t('userManagement.colUsername')}
            </label>
            <Input
              id='create-user-username'
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              maxLength={64}
              autoFocus
              disabled={submitting}
            />
          </div>
          <div className='space-y-1.5'>
            <label htmlFor='create-user-password' className='text-sm'>
              {t('userManagement.colPassword')}
            </label>
            <Input
              id='create-user-password'
              type='password'
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={PASSWORD_MIN_LENGTH}
              disabled={submitting}
            />
          </div>
          <div className='space-y-1.5'>
            <label htmlFor='create-user-confirm' className='text-sm'>
              {t('userManagement.colConfirmPassword')}
            </label>
            <Input
              id='create-user-confirm'
              type='password'
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              minLength={PASSWORD_MIN_LENGTH}
              disabled={submitting}
            />
            {confirmPassword.length > 0 &&
              confirmPassword !== password && (
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
          <div className='space-y-1.5'>
            <label htmlFor='create-user-nickname' className='text-sm'>
              {t('userManagement.colNickname')}
            </label>
            <Input
              id='create-user-nickname'
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              maxLength={64}
              placeholder={t('userManagement.nicknamePlaceholder')}
              disabled={submitting}
            />
          </div>
        </div>

        <DialogFooter>
          <Button
            variant='outline'
            onClick={() => onOpenChange(false)}
            disabled={submitting}
          >
            {t('userManagement.cancel')}
          </Button>
          <Button onClick={handleCreate} disabled={!canSubmit}>
            {submitting
              ? t('userManagement.creating')
              : t('userManagement.create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
