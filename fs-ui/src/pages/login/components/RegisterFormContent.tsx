import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { userApi } from '@/api'
import { UserRegisterParams } from '@/types/user'
import { User, Lock, Pen } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import FieldBox, { fieldInputClass } from './FieldBox'

interface Props {
  onSwitchForm: (form: 'login' | 'register') => void
}

export default function RegisterFormContent({ onSwitchForm }: Props) {
  const { t } = useTranslation('login')
  const [loading, setLoading] = useState(false)

  const [formData, setFormData] = useState<UserRegisterParams>({
    username: '',
    password: '',
    confirmPassword: '',
    nickname: '',
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    if (formData.password !== formData.confirmPassword) {
      toast.error(t('toast.passwordMismatch'))
      return
    }

    setLoading(true)
    try {
      await userApi.register(formData)
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
      {/* 与登录页一致：标题在右让视觉重心偏右，整组左移 8px 做光学校正（定值，避开 15px 根字号对 spacing 的缩放） */}
      <div className='flex -translate-x-[8px] items-center justify-center gap-4'>
        <img
          src='/logo.png'
          alt='GFS'
          className='h-14 w-14 rounded-xl object-contain'
        />
        <h3 className='text-3xl font-bold text-[#3573FF]'>
          {t('createAccount')}
        </h3>
      </div>

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
