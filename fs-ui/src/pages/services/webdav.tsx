import { useTranslation } from 'react-i18next'
import { useServiceSettings } from './hooks/useServiceSettings'
import { ServiceSettingCard } from './components/ServiceSettingCard'
import { UsageTips } from './components/UsageTips'

/**
 * WebDAV 服务配置页（serviceType=webdav），通用布局由 ServicePage 提供
 */
export function WebDavServicePage({ serviceType = 'webdav' }: { serviceType?: 'webdav' | 'sftp' }) {
  return <ServicePage serviceType={serviceType} />
}

export default WebDavServicePage

function ServicePage({ serviceType }: { serviceType: 'webdav' | 'sftp' }) {
  const { t } = useTranslation('services')
  const { data: settings = [], isLoading } = useServiceSettings()
  const setting = settings.find((s) => s.serviceType === serviceType)

  return (
    <div className='flex h-full flex-col'>
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 py-3 sm:px-6 sm:py-4'>
        <div className='flex h-9 w-full min-w-0 items-center sm:w-auto sm:flex-1'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('page.title')}
          </h2>
        </div>
      </div>

      <div className='flex-1 overflow-auto px-3 pb-6 sm:px-6'>
        {isLoading ? (
          <div className='flex h-64 items-center justify-center'>
            <p className='text-muted-foreground'>{t('page.loading')}</p>
          </div>
        ) : !setting ? (
          <div className='flex h-64 items-center justify-center rounded-lg border-2 border-dashed'>
            <p className='text-muted-foreground'>{t('page.loadFailed')}</p>
          </div>
        ) : (
          <div className='mx-auto max-w-2xl space-y-4'>
            <p className='text-sm text-muted-foreground'>
              {t('page.description')}
            </p>
            <ServiceSettingCard setting={setting} />
            <UsageTips serviceType={serviceType} />
          </div>
        )}
      </div>
    </div>
  )
}
