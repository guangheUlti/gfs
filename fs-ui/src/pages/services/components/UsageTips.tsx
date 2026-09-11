import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'

/** 底部挂载用法提示块（Windows 映射 / rclone / sshfs / WinSCP 示例） */
export function UsageTips({ serviceType }: { serviceType: 'webdav' | 'sftp' }) {
  const { t } = useTranslation('services')

  const keys: string[] =
    serviceType === 'webdav'
      ? ['windows', 'rclone', 'basic', 'lock']
      : ['client', 'sshfs', 'winscp', 'readonly']

  return (
    <div className='rounded-lg border bg-muted/40 px-5 py-4'>
      <div className='flex items-center gap-2'>
        <Info className='h-4 w-4 text-muted-foreground' />
        <h4 className='text-sm font-medium'>{t('usage.title')}</h4>
      </div>
      <ul className='mt-2 list-disc space-y-1.5 pl-7 text-xs leading-relaxed text-muted-foreground'>
        {keys.map((key) => (
          <li key={key}>{t(`usage.${serviceType}.${key}`, { port: 9022 })}</li>
        ))}
      </ul>
    </div>
  )
}
