import { useTranslation } from 'react-i18next'
import type { ServiceSettingVO } from '@/api/service'
import { useServiceSettings } from './hooks/useServiceSettings'
import { ServiceSettingCard } from './components/ServiceSettingCard'
import { UsageTips } from './components/UsageTips'

/**
 * 对外文件服务配置页：全部 SPI 注册服务卡片并列展示（webdav/sftp/ftp…）
 * 服务卡片内容多，不跟存储配置页共用等分网格，大屏下一行两张等宽卡片
 */
/** 卡片展示顺序：ftp → sftp → webdav → oss（oss 放最后） */
function serviceOrder(type: string): number {
  const order = ['ftp', 'sftp', 'webdav', 'oss']
  const idx = order.indexOf(type)
  return idx === -1 ? order.length : idx
}

export default function ServicesPage() {
  const { t } = useTranslation('services')
  const { data: settings = [], isLoading } = useServiceSettings()

  return (
    <div className='flex h-full flex-col'>
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        <div className='flex h-9 w-full min-w-0 items-center sm:w-auto sm:flex-1'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('page.title')}
          </h2>
        </div>
      </div>

      <div className='flex-1 overflow-auto px-3 pt-4 pb-3 sm:px-6 sm:pt-5 sm:pb-6'>
        {isLoading ? (
          <div className='flex h-64 items-center justify-center'>
            <p className='text-muted-foreground'>{t('page.loading')}</p>
          </div>
        ) : (
          <div className='grid grid-cols-1 gap-4 lg:grid-cols-2'>
            {[...settings]
              .sort((a, b) => serviceOrder(a.serviceType) - serviceOrder(b.serviceType))
              .map((setting) => (
                <ServiceColumn
                  key={setting.serviceType}
                  serviceType={setting.serviceType}
                  setting={setting}
                />
              ))}
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
  serviceType: ServiceSettingVO['serviceType']
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
      <UsageTips setting={setting} />
    </ServiceSettingCard>
  )
}
