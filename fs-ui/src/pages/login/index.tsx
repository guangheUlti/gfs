import React, { Suspense, useState } from 'react'
import { useTranslation } from 'react-i18next'
import LoginFormContent from './components/LoginFormContent'
import RegisterFormContent from './components/RegisterFormContent'
import { LoginLanguageSwitcher } from './components/LoginLanguageSwitcher'
import CornerTop from './components/CornerTop'
import CornerBottom from './components/CornerBottom'
import LegalDialog, { type LegalType } from './components/LegalDialog'
import { useSearchParams } from 'react-router-dom'

const LoginPage: React.FC = () => {
  const { t } = useTranslation('login')
  const [searchParams, setSearchParams] = useSearchParams()
  const [legalType, setLegalType] = useState<LegalType>(null)
  const type = searchParams.get('type') || 'login'

  const handleSwitchForm = (form: 'login' | 'register') => {
    setSearchParams({ type: form })
  }

  const inviteToken = searchParams.get('token') || undefined

  const renderContent = () => {
    switch (type) {
      case 'register':
        return (
          <RegisterFormContent
            onSwitchForm={handleSwitchForm}
            inviteToken={inviteToken}
          />
        )
      default:
        return <LoginFormContent onSwitchForm={handleSwitchForm} />
    }
  }

  return (
    <div className='relative flex min-h-screen w-full items-center justify-center overflow-hidden p-4'>
      {/* 全屏背景（alist 旧版风格：浅蓝紫纯色 + 角部渐变装饰） */}
      <div
        className='fixed inset-0 z-0 overflow-hidden bg-[#a9c6ff] dark:bg-[#062b74]'
        aria-hidden
      >
        <div className='absolute -top-[1170px] -right-[100px] sm:-top-[900px] sm:-right-[300px]'>
          <CornerTop />
        </div>
        <div className='absolute -bottom-[760px] -left-[100px] sm:-bottom-[400px] sm:-left-[200px]'>
          <CornerBottom />
        </div>
      </div>

      {/* 登录卡片 + 底部信息 */}
      <div className='relative z-10 flex w-full max-w-[420px] flex-col items-center gap-6 py-8'>
        <div className='flex w-full flex-col gap-4 rounded-xl bg-white p-6 shadow-[0_8px_30px_rgba(0,0,0,0.18)] dark:bg-neutral-900'>
          <Suspense
            fallback={
              <div className='flex h-[240px] items-center justify-center'>
                <div className='h-6 w-6 animate-spin rounded-full border-2 border-[#3573FF] border-t-transparent' />
              </div>
            }
          >
            {renderContent()}
          </Suspense>
        </div>

        {/* 底部信息（浅蓝背景上用深色文字，暗色模式下用白色） */}
        <div className='flex flex-col items-center gap-3 text-xs text-slate-600/80 dark:text-white/60'>
          <div className='flex flex-wrap items-center justify-center gap-4'>
            <LoginLanguageSwitcher />
            <button
              type='button'
              onClick={() => setLegalType('terms')}
              className='cursor-pointer transition-colors hover:text-slate-800 dark:hover:text-white/90'
            >
              {t('termsOfService')}
            </button>
            <button
              type='button'
              onClick={() => setLegalType('privacy')}
              className='cursor-pointer transition-colors hover:text-slate-800 dark:hover:text-white/90'
            >
              {t('privacyPolicy')}
            </button>
          </div>
          <p>{t('footerCopyright')} @guangheUlti</p>
        </div>
      </div>

      {/* 用户协议 / 隐私政策弹窗 */}
      <LegalDialog
        type={legalType}
        onOpenChange={(open) => {
          if (!open) setLegalType(null)
        }}
      />
    </div>
  )
}
export default LoginPage
