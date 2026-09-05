import { useTranslation } from 'react-i18next'
import { getAppLang } from '@/i18n'
import { cn } from '@/lib/utils'

export function LoginLanguageSwitcher() {
  const { i18n } = useTranslation()
  const lang = getAppLang()

  return (
    <div className='flex items-center gap-2 text-xs text-slate-600/80 dark:text-white/60'>
      <button
        type='button'
        onClick={() => void i18n.changeLanguage('zh')}
        className={cn(
          'transition-colors hover:text-slate-800 dark:hover:text-white/90',
          lang === 'zh' && 'font-medium text-slate-900 dark:text-white'
        )}
      >
        中文
      </button>
      <span aria-hidden className='text-slate-400/60 dark:text-white/40'>
        |
      </span>
      <button
        type='button'
        onClick={() => void i18n.changeLanguage('en')}
        className={cn(
          'transition-colors hover:text-slate-800 dark:hover:text-white/90',
          lang === 'en' && 'font-medium text-slate-900 dark:text-white'
        )}
      >
        English
      </button>
    </div>
  )
}
