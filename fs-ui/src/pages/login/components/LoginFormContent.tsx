import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { userApi } from '@/api'
import { useAuth } from '@/contexts/auth-context'
import type { LoginParams } from '@/types/user'
import { User, Lock, Eye, EyeOff } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { setToken } from '@/utils/auth'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import FieldBox, { fieldInputClass } from './FieldBox'

interface Props {
  onSwitchForm: (form: 'login' | 'register') => void
}

function getSafeRedirectPath(searchParams: URLSearchParams): string | null {
  const raw = searchParams.get('redirect')
  if (!raw) return null
  try {
    const decoded = decodeURIComponent(raw)
    if (!decoded.startsWith('/') || decoded.startsWith('//')) return null
    return decoded
  } catch {
    return null
  }
}

export default function LoginFormContent({ onSwitchForm }: Props) {
  const { t } = useTranslation('login')
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { login } = useAuth()
  const [loading, setLoading] = useState(false)
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [isRemember, setIsRemember] = useState(true)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    const acc = account.trim()
    if (!acc) {
      toast.error(t('toast.enterUsernameOrEmail'))
      return
    }
    if (!password) {
      toast.error(t('toast.enterPassword'))
      return
    }

    const payload: LoginParams = {
      loginType: 'password',
      account: acc,
      password,
      isRemember,
    }

    setLoading(true)
    try {
      const res = await userApi.login(payload)
      const { accessToken } = res

      setToken(accessToken, isRemember)

      const userInfo = await userApi.getUserInfo()

      await login(accessToken, userInfo, isRemember)

      toast.success(t('toast.loginSuccess'))
      const next = getSafeRedirectPath(searchParams) ?? '/'
      navigate(next, { replace: true })
    } catch {
      // Error handled by interceptor
    } finally {
      setLoading(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className='flex flex-col gap-4'>
      {/* 卡片头部：logo + 站名同行（对齐目标站样式） */}
      {/* 这组元素几何上是正中的，但右侧长标题让视觉重心偏右，故整体左移 8px 做光学校正 */}
      {/* 根字号为 15px，spacing 类会打折扣（-translate-x-2 实测只有 7.5px），因此用定值 */}
      <div className='flex -translate-x-[8px] items-center justify-center gap-4'>
        <img
          src='/logo.png'
          alt='GFS'
          className='h-14 w-14 rounded-xl object-contain'
        />
        <h3 className='text-3xl font-bold text-[#3573FF]'>
          {t('siteTitle')}
        </h3>
      </div>

      <FieldBox icon={<User className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='account'
          type='text'
          placeholder={t('placeholderUsernameOrEmail')}
          value={account}
          onChange={(e) => setAccount(e.target.value)}
          className={fieldInputClass}
          autoComplete='username'
          required
        />
      </FieldBox>

      <FieldBox icon={<Lock className='h-5 w-5 shrink-0 text-neutral-400' />}>
        <Input
          id='password'
          type={showPassword ? 'text' : 'password'}
          placeholder={t('placeholderPassword')}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className={fieldInputClass}
          autoComplete='current-password'
          required
        />
        <button
          type='button'
          onClick={() => setShowPassword(!showPassword)}
          aria-label={showPassword ? t('hidePassword') : t('showPassword')}
          className='shrink-0 rounded-md p-1.5 text-neutral-400 transition-colors hover:bg-neutral-100 hover:text-neutral-600 dark:hover:bg-neutral-800 dark:hover:text-neutral-300'
        >
          {showPassword ? (
            <EyeOff className='h-5 w-5' />
          ) : (
            <Eye className='h-5 w-5' />
          )}
        </button>
      </FieldBox>

      <div className='flex w-full items-center justify-between px-1 text-sm text-neutral-500 dark:text-neutral-400'>
        <div className='flex items-center space-x-2'>
          <Checkbox
            id='remember'
            checked={isRemember}
            onCheckedChange={(checked) => setIsRemember(checked as boolean)}
          />
          <label
            htmlFor='remember'
            className='cursor-pointer select-none'
          >
            {t('rememberMe')}
          </label>
        </div>
      </div>

      <Button
        type='submit'
        disabled={loading}
        className='mt-2 h-[45px] w-full rounded-xl bg-[#3573FF] text-base font-bold text-white hover:bg-[#2B5CD9] active:bg-[#1E40AF]'
      >
        {loading ? t('loggingIn') : t('login')}
      </Button>

      <div className='flex w-full items-center justify-between text-sm'>
        <span className='text-neutral-500 dark:text-neutral-400'>
          {t('noAccount')}
        </span>
        <button
          type='button'
          className='cursor-pointer text-[#3573FF] hover:underline'
          onClick={() => onSwitchForm('register')}
        >
          {t('registerNow')}
        </button>
      </div>
    </form>
  )
}
