import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Check, ChevronDown, Database } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { getActiveStoragePlatforms } from '@/api/storage'
import type { ActiveStoragePlatform } from '@/types/storage'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

const STORAGE_KEY = 'current-storage-platform'

/** 未写入 localStorage = 内置本地存储（请求头不携带 configId） */
const readCurrent = (): { settingId: string; platformName?: string } => {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')
    if (stored?.settingId) {
      return { settingId: stored.settingId, platformName: stored.platformName }
    }
  } catch {
    // 本地数据异常按未选择处理
  }
  return { settingId: 'Local' }
}

interface StorageSwitcherProps {
  onChanged: () => void
}

export function StorageSwitcher({ onChanged }: StorageSwitcherProps) {
  const { t } = useTranslation('files')
  const [current, setCurrent] = useState(readCurrent)

  const { data: platforms = [] } = useQuery({
    queryKey: ['activeStoragePlatforms'],
    queryFn: getActiveStoragePlatforms,
    staleTime: 30_000,
  })

  // 仅一个可用存储时切换器没有意义
  if (platforms.length <= 1) {
    return null
  }

  const displayName =
    platforms.find((p) => p.settingId === current.settingId)?.platformName ||
    current.platformName ||
    t('index.storageLocal')

  const handleSelect = (platform: ActiveStoragePlatform) => {
    if (platform.settingId === current.settingId) return
    if (platform.settingId === 'Local') {
      localStorage.removeItem(STORAGE_KEY)
    } else {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          settingId: platform.settingId,
          platformName: platform.platformName,
        })
      )
    }
    setCurrent({ settingId: platform.settingId, platformName: platform.platformName })
    onChanged()
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant='outline'
          size='sm'
          className='shrink-0 gap-1.5'
          aria-label={t('index.storageSwitch')}
        >
          <Database className='h-4 w-4 opacity-70' />
          <span className='max-w-32 truncate sm:max-w-40'>{displayName}</span>
          <ChevronDown className='h-4 w-4 opacity-70' />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align='start' className='min-w-48'>
        {platforms.map((platform) => (
          <DropdownMenuItem
            key={platform.settingId}
            onSelect={() => handleSelect(platform)}
            className='gap-2'
          >
            <Check
              className={`h-4 w-4 shrink-0 ${
                platform.settingId === current.settingId
                  ? 'opacity-100'
                  : 'opacity-0'
              }`}
            />
            <span className='truncate'>{platform.platformName}</span>
            {platform.remark && (
              <span className='truncate text-xs text-muted-foreground'>
                {platform.remark}
              </span>
            )}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
