import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import type { ServiceSettingVO } from '@/api/service'

/** 底部挂载用法提示块（Windows 映射 / rclone / sshfs / WinSCP / ftp 示例），端口取自服务配置 */
export function UsageTips({ setting }: { setting: ServiceSettingVO }) {
  const { t } = useTranslation('services')
  const serviceType = setting.serviceType
  const port = setting.port ?? 80

  const keysByType: Record<string, string[]> = {
    webdav: ['windows', 'rclone', 'basic', 'lock'],
    sftp: ['client', 'sshfs', 'winscp', 'readonly'],
    ftp: ['client', 'filezilla', 'pasv', 'security'],
    oss: ['client', 'keys', 'prefix', 'semantics'],
  }
  const keys = keysByType[serviceType] ?? ['client']

  return (
    <div className='border-t bg-muted/40 px-5 py-4'>
      <div className='flex items-center gap-2'>
        <Info className='h-4 w-4 text-muted-foreground' />
        <h4 className='text-sm font-medium'>{t('usage.title')}</h4>
      </div>
      <ul className='mt-2 list-disc space-y-1.5 pl-7 text-xs leading-relaxed text-muted-foreground'>
        {keys.map((key) => (
          <li key={key}>{t(`usage.${serviceType}.${key}`, { port })}</li>
        ))}
      </ul>
    </div>
  )
}
