import { AlertCircle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ServiceSettingVO } from '@/api/service'
import { Badge } from '@/components/ui/badge'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'

type StatusVariant = 'default' | 'secondary' | 'destructive' | 'outline'

const STATUS_VARIANT: Record<ServiceSettingVO['status'], StatusVariant> = {
  running: 'default',
  stopped: 'secondary',
  error: 'destructive',
}

/** 运行状态徽标：running=绿 / stopped=灰 / error=红 + 错误信息 tooltip */
export function ServiceStatusBadge({
  setting,
}: {
  setting: ServiceSettingVO
}) {
  const { t } = useTranslation('services')
  const status: ServiceSettingVO['status'] = setting.status ?? 'stopped'
  const isError = status === 'error'

  const label =
    status === 'stopped' && !setting.enabled
      ? t('status.stoppedUnavailable')
      : t(`status.${status}`)

  const badge = (
    <Badge
      variant={STATUS_VARIANT[status] ?? 'secondary'}
      className={
        status === 'running' ? 'bg-emerald-600 text-white hover:bg-emerald-600' : undefined
      }
    >
      {label}
    </Badge>
  )

  if (!isError || !setting.error) return badge

  return (
    <TooltipProvider delayDuration={200}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span className='inline-flex cursor-help items-center gap-1'>
            {badge}
            <AlertCircle className='h-3.5 w-3.5 text-destructive' />
          </span>
        </TooltipTrigger>
        <TooltipContent className='max-w-xs break-all'>
          {setting.error}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
