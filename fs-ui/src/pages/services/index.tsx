import { useTranslation } from 'react-i18next'
import type { ServiceSettingVO } from '@/api/service'
import { useServiceSettings } from './hooks/useServiceSettings'
import { ServiceSettingCard } from './components/ServiceSettingCard'
import { UsageTips } from './components/UsageTips'

/**
 * 对外文件服务配置页：WebDAV 与 SFTP 两张卡片并列展示（布局对齐存储配置页的卡片网格）
 */
export default function ServicesPage() {
  const { t } = useTranslation('services')
  const { data: settings = [], isLoading } = useServiceSettings()

  const findByType = (serviceType: 'webdav' | 'sftp') =>
    settings.find((s) => s.serviceType === serviceType)

  return (
    <div className='flex h-full flex-col'>
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        <div className='flex h-9 w-full min-w-0 items-center sm:w-auto sm:flex-1'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('page.title')}
          </h2>
        </div>
      </div>

      <div className='flex-1 overflow-auto px-3 pt-1 pb-3 sm:px-6 sm:pt-1.5 sm:pb-6'>
        {isLoading ? (
          <div className='flex h-64 items-center justify-center'>
            <p className='text-muted-foreground'>{t('page.loading')}</p>
          </div>
        ) : (
          <div className='grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5'>
            <ServiceColumn
              serviceType='webdav'
              setting={findByType('webdav')}
            />
            <ServiceColumn serviceType='sftp' setting={findByType('sftp')} />
          </div>
        )}
      </div>
    </div>
  )
}

function ServiceColumn({
  serviceType,
  setting,
}: {
  serviceType: 'webdav' | 'sftp'
  setting?: ServiceSettingVO
}) {
  const { t } = useTranslation('services')

  if (!setting) {
    return (
      <div className='flex h-40 items-center justify-center rounded-lg border-2 border-dashed'>
        <p className='text-muted-foreground'>{t('page.loadFailed')}</p>
      </div>
    )
  }

  return (
    <ServiceSettingCard setting={setting}>
      <UsageTips serviceType={serviceType} />
    </ServiceSettingCard>
  )
}
