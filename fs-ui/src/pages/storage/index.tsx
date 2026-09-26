import { type ChangeEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RefreshCw, Plus, Search } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { getUserStorageSettings } from '@/api/storage'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequirePermission } from '@/components/require-permission'
import { AddStorageModal } from './components/AddStorageModal'
import { StorageSettingCard } from './components/StorageSettingCard'

export default function StoragePage() {
  const { t } = useTranslation('storage')
  const { t: tc } = useTranslation('common')
  const [searchTerm, setSearchTerm] = useState('')
  const [addModalVisible, setAddModalVisible] = useState(false)

  const {
    data: userSettings = [],
    isLoading,
    refetch,
  } = useQuery({
    queryKey: ['userStorageSettings'],
    queryFn: getUserStorageSettings,
    staleTime: 30_000,
  })

  // 过滤配置：内置本地存储（固定 id=Local）固定首位，其余按平台名称升序保持列表稳定。
  // 注意不能用 identifier 判定：附加本地存储实例 identifier 同样是 Local，但 id 是 UUID
  const filteredSettings = userSettings
    .sort((a, b) => {
      if (a.id === 'Local') return -1
      if (b.id === 'Local') return 1
      return a.storagePlatform.name.localeCompare(b.storagePlatform.name)
    })
    .filter((s) => {
      if (!searchTerm) return true
      const keyword = searchTerm.toLowerCase()
      return (
        s.storagePlatform.name.toLowerCase().includes(keyword) ||
        s.storagePlatform.identifier.toLowerCase().includes(keyword) ||
        (s.remark || '').toLowerCase().includes(keyword)
      )
    })

  const handleSearch = (e: ChangeEvent<HTMLInputElement>) => {
    setSearchTerm(e.target.value)
  }

  return (
    <div className='flex h-full flex-col'>
      {/* 顶部工具栏：窄屏时标题独占一行，搜索与按钮同处第二行 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        {/* 标题 */}
        <div className='flex h-9 w-full min-w-0 items-center sm:w-auto sm:flex-1'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('page.title')}
          </h2>
        </div>

        {/* 右侧工具栏 */}
        <div className='flex w-full items-center gap-2 sm:w-auto'>
          <div className='relative min-w-0 flex-1 sm:w-[250px] sm:flex-none'>
            <Search className='absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-muted-foreground' />
            <Input
              placeholder={t('page.searchPlaceholder')}
              className='h-9 w-full pl-10'
              value={searchTerm}
              onChange={handleSearch}
            />
          </div>
          <Button
            variant='outline'
            size='icon'
            className='shrink-0'
            onClick={() => refetch()}
            aria-label={tc('refresh')}
          >
            <RefreshCw className='h-4 w-4' />
          </Button>
          <RequirePermission code='storage:manage'>
            <Button
              size='sm'
              className='shrink-0'
              onClick={() => setAddModalVisible(true)}
            >
              <Plus className='mr-1.5 h-4 w-4' />
              {t('page.add')}
            </Button>
          </RequirePermission>
        </div>
      </div>

      {/* 主内容区域 */}
      <div className='flex-1 overflow-auto px-3 pt-4 pb-3 sm:px-6 sm:pt-5 sm:pb-6'>
        {isLoading ? (
          <div className='flex h-64 items-center justify-center'>
            <p className='text-muted-foreground'>{tc('loading')}</p>
          </div>
        ) : filteredSettings.length === 0 ? (
          <div className='flex h-64 items-center justify-center rounded-lg border-2 border-dashed'>
            <p className='text-muted-foreground'>{t('page.noMatch')}</p>
          </div>
        ) : (
          <ul className='faded-bottom no-scrollbar grid grid-cols-1 gap-4 overflow-auto pb-16 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5'>
            {filteredSettings.map((setting) => (
              <StorageSettingCard
                key={setting.id}
                setting={setting}
                onRefresh={refetch}
              />
            ))}
          </ul>
        )}
      </div>

      {/* Add Storage Config Modal */}
      <AddStorageModal
        open={addModalVisible}
        onOpenChange={setAddModalVisible}
        onSuccess={refetch}
      />
    </div>
  )
}
