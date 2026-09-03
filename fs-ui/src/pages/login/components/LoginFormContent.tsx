import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { userApi } from '@/api'
import { useAuth } from '@/contexts/auth-context'
import type { LoginParams } from '@/types/user'
import { User, Lock } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { setToken } from '@/utils/auth'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'

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
    <form onSubmit={handleSubmit} className='space-y-6'>
      <div className='space-y-2 text-center'>
        <h3 className='text-2xl font-bold tracking-tight'>{t('welcomeBack')}</h3>
        <p className='text-sm text-muted-foreground'>{t('loginAccount')}</p>
      </div>

      <div className='space-y-3'>
        <div className='relative'>
          <User className='absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-muted-foreground' />
          <Input
            id='account'
            type='text'
            placeholder={t('placeholderUsernameOrEmail')}
            value={account}
            onChange={(e) => setAccount(e.target.value)}
            className='h-11 pl-10'
            autoComplete='username'
            required
          />
        </div>
      </div>

      <div className='space-y-3'>
        <div className='relative'>
          <Lock className='absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-muted-foreground' />
          <Input
            id='password'
            type='password'
            placeholder={t('placeholderPassword')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className='h-11 pl-10'
            autoComplete='current-password'
            required
          />
        </div>
      </div>

      <div className='flex items-center justify-between gap-2'>
        <div className='flex items-center space-x-2'>
          <Checkbox
            id='remember'
            checked={isRemember}
            onCheckedChange={(checked) => setIsRemember(checked as boolean)}
          />
          <label
            htmlFor='remember'
            className='cursor-pointer text-sm text-muted-foreground select-none'
          >
            {t('rememberMe')}
          </label>
        </div>
      </div>

      <Button type='submit' className='h-11 w-full' disabled={loading}>
        {loading ? t('loggingIn') : t('login')}
      </Button>

      <p className='text-center text-sm text-muted-foreground'>
        {t('noAccount')}{' '}
        <button
          type='button'
          className='underline underline-offset-2 hover:text-foreground'
          onClick={() => onSwitchForm('register')}
        >
          {t('registerNow')}
        </button>
      </p>
    </form>
  )
}
