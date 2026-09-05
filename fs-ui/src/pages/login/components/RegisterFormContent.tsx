import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { userApi } from '@/api'
import { UserRegisterParams } from '@/types/user'
import { User, Lock, Mail, Pen, Info } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import FieldBox, { fieldInputClass } from './FieldBox'

interface Props {
  onSwitchForm: (form: 'login' | 'register') => void
  inviteToken?: string
}

export default function RegisterFormContent({ onSwitchForm, inviteToken }: Props) {
  const { t } = useTranslation('login')
  const [searchParams] = useSearchParams()
  const [loading, setLoading] = useState(false)

  // 从 URL 获取邀请邮箱参数
  const inviteEmail = searchParams.get('email')

  const [formData, setFormData] = useState<UserRegisterParams>({
    username: '',
    password: '',
    confirmPassword: '',
    email: inviteEmail || '',
    nickname: '',
    inviteToken: inviteToken || undefined,
  })

  const hasInvitation = !!(inviteToken || formData.inviteToken)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    if (formData.password !== formData.confirmPassword) {
      toast.error(t('toast.passwordMismatch'))
      return
    }

    const submitData = formData.inviteToken
      ? { ...formData, inviteToken: formData.inviteToken }
      : formData

    setLoading(true)
    try {
      await userApi.register(submitData)
      // 新注册账号需管理员审核通过后才允许登录，不再自动登录
      toast.success(t('toast.registerPendingReview'))
      setTimeout(() => {
        onSwitchForm('login')
      }, 1500)
    } finally {
      setLoading(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className='flex flex-col gap-4'>
      {/* 卡片头部：logo + 标题同行（对齐目标站样式） */}
      <div className='flex items-center justify-center gap-4'>
        <img
          src='/logo.png'
          alt='GFS'
          className='h-14 w-14 rounded-xl object-contain'
        />
        <h3 className='text-3xl font-bold text-[#3573FF]'>
          {t('createAccount')}
        </h3>
      </div>
      {/* 副标题只在邀请注册时出现：普通注册已有「创建账号」标题，不需要再补一行 */}
      {hasInvitation && (
        <p className='-mt-2 text-center text-xs text-neutral-500 dark:text-neutral-400'>
          {t('registerSubtitleInvite')}
        </p>
      )}

      {/* 邀请注册提示 */}
      {hasInvitation && (
        <div className='rounded-xl border border-[#3573FF]/30 bg-[#3573FF]/5 p-3 text-sm text-[#2B5CD9] dark:text-[#8fb0ff]'>
          <div className='flex items-start gap-2'>
            <Info className='mt-0.5 h-4 w-4 shrink-0' />
            <div>
              <p className='font-medium'>{t('invitationRegisterNotice')}</p>
              <p className='mt-1 text-xs opacity-80'>
                {t('autoJoinWorkspace')}
              </p>
            </div>
          </div>
        </div>
      )}

      <FieldBox icon={<User className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='register-username'
          type='text'
          placeholder={t('placeholderUsername')}
          value={formData.username}
          onChange={(e) =>
            setFormData({ ...formData, username: e.target.value })
          }
          className={fieldInputClass}
          disabled={loading}
          required
        />
      </FieldBox>

      <FieldBox icon={<Lock className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='register-password'
          type='password'
          placeholder={t('placeholderPassword')}
          value={formData.password}
          onChange={(e) =>
            setFormData({ ...formData, password: e.target.value })
          }
          className={fieldInputClass}
          disabled={loading}
          required
          minLength={6}
        />
      </FieldBox>

      <FieldBox icon={<Lock className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='register-confirm-password'
          type='password'
          placeholder={t('placeholderConfirmPassword')}
          value={formData.confirmPassword}
          onChange={(e) =>
            setFormData({ ...formData, confirmPassword: e.target.value })
          }
          className={fieldInputClass}
          disabled={loading}
          required
          minLength={6}
        />
      </FieldBox>

      <FieldBox icon={<Mail className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='register-email'
          type='email'
          placeholder={t('placeholderEmail')}
          value={formData.email}
          onChange={(e) => setFormData({ ...formData, email: e.target.value })}
          className={fieldInputClass}
          disabled={loading || hasInvitation}
          required
        />
      </FieldBox>
      {hasInvitation && (
        <p className='-mt-2 px-1 text-xs text-neutral-500 dark:text-neutral-400'>
          {t('emailFixedByInvite')}
        </p>
      )}

      <FieldBox icon={<Pen className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='register-nickname'
          type='text'
          placeholder={t('placeholderNicknameOptional')}
          value={formData.nickname}
          onChange={(e) =>
            setFormData({ ...formData, nickname: e.target.value })
          }
          className={fieldInputClass}
          disabled={loading}
        />
      </FieldBox>

      <Button
        type='submit'
        disabled={loading}
        className='mt-2 h-[45px] w-full rounded-xl bg-[#3573FF] text-base font-bold text-white hover:bg-[#2B5CD9] active:bg-[#1E40AF]'
      >
        {loading ? t('registering') : t('register')}
      </Button>

      <div className='flex w-full items-center justify-between text-sm'>
        <span className='text-neutral-500 dark:text-neutral-400'>
          {t('hasAccount')}
        </span>
        <button
          type='button'
          className='cursor-pointer text-[#3573FF] hover:underline'
          onClick={() => onSwitchForm('login')}
          disabled={loading}
        >
          {t('backToLogin')}
        </button>
      </div>
    </form>
  )
}
