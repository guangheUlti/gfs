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

interface EditUserDialogProps {
  user: AdminUser | null
  onClose: () => void
  onSaved: () => void
}

/** 管理员编辑账号资料：用户名是登录标识不可改，只能改昵称 */
export function EditUserDialog({ user, onClose, onSaved }: EditUserDialogProps) {
  const { t } = useTranslation('settings')
  const [nickname, setNickname] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (user) setNickname(user.nickname || '')
  }, [user])

  const canSubmit = nickname.trim().length > 0 && !saving

  const handleSave = async () => {
    if (!user || !canSubmit) return
    setSaving(true)
    try {
      await adminApi.updateUser(user.id, { nickname: nickname.trim() })
      toast.success(t('userManagement.saved'))
      onClose()
      onSaved()
    } catch (err) {
      if (!isHandledError(err)) toast.error(t('userManagement.actionFailed'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={!!user} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className='sm:max-w-[420px]'>
        <DialogHeader>
          <DialogTitle>{t('userManagement.editTitle')}</DialogTitle>
          <DialogDescription>
            {t('userManagement.editDescription')}
          </DialogDescription>
        </DialogHeader>

        <div className='space-y-1.5'>
          <label htmlFor='edit-user-nickname' className='text-sm'>
            {t('userManagement.nickname')}
          </label>
          <Input
            id='edit-user-nickname'
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={64}
            autoFocus
            disabled={saving}
            onKeyDown={(e) => e.key === 'Enter' && handleSave()}
          />
          <p className='text-xs text-muted-foreground'>{user?.username}</p>
        </div>

        <DialogFooter>
          <Button variant='outline' onClick={onClose} disabled={saving}>
            {t('userManagement.cancel')}
          </Button>
          <Button onClick={handleSave} disabled={!canSubmit}>
            {saving ? t('userManagement.saving') : t('userManagement.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
