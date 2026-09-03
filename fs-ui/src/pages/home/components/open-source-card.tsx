import { useTranslation } from 'react-i18next'
import { Code } from 'lucide-react'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { cn } from '@/lib/utils'

export function OpenSourceCard({ className }: { className?: string }) {
  const { t } = useTranslation('home')
  return (
    <Card className={cn('gap-0 overflow-hidden py-0 shadow-sm', className)}>
      <CardHeader className='gap-0 border-b border-border/60 px-4 pb-3 pt-4'>
        <CardTitle className='text-base'>{t('openSource.title')}</CardTitle>
      </CardHeader>
      <CardContent className='flex flex-col gap-4 px-4 pb-4 pt-3'>
        <p className='text-muted-foreground text-sm leading-relaxed'>
          {t('openSource.description')}
        </p>
        <div className='flex flex-col gap-2'>
          <a
            href='https://github.com/guangheUlti/gfs'
            target='_blank'
            rel='noopener noreferrer'
            className='group flex items-center justify-center gap-2 rounded-lg bg-muted/70 px-3 py-2.5 text-sm font-semibold text-foreground transition-colors hover:bg-muted dark:bg-muted/50'
          >
            <Code className='size-4 shrink-0' aria-hidden />
            {t('openSource.github')}
          </a>
        </div>
      </CardContent>
    </Card>
  )
}
