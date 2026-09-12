import { useState, useEffect } from 'react'
import { useTransferStore } from '@/store/transfer'
import { RefreshCw, Upload, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import TransferTable from './components/TransferTable'

export default function TransferPage() {
  const { t } = useTranslation('transfer')
  const { t: tc } = useTranslation('common')
  const [activeTab, setActiveTab] = useState('uploading')
  const [loading, setLoading] = useState(false)

  const {
    getUploadingTasks,
    getDownloadingTasks,
    getCompletedTasks,
    fetchTasks,
    pauseTask,
    resumeTask,
    cancelTask,
    retryTask,
    clearCompletedTasks,
    sseConnected,
  } = useTransferStore()

  const uploadingTasks = getUploadingTasks()
  const downloadingTasks = getDownloadingTasks()
  const completedTasks = getCompletedTasks()

  const currentDisplayTasks =
    activeTab === 'uploading'
      ? uploadingTasks
      : activeTab === 'downloading'
        ? downloadingTasks
        : completedTasks

  useEffect(() => {
    const initTransfer = async () => {
      if (sseConnected) {
        setLoading(true)
        try {
          await fetchTasks()
        } catch (error) {
          console.error('获取传输列表失败:', error)
        } finally {
          setLoading(false)
        }
      }
    }

    initTransfer()
  }, [sseConnected])

  // 监听tab切换，刷新数据
  useEffect(() => {
    if (sseConnected && activeTab === 'completed') {
      const refreshData = async () => {
        setLoading(true)
        try {
          await fetchTasks()
        } catch (error) {
          console.error('刷新数据失败:', error)
        } finally {
          setLoading(false)
        }
      }
      refreshData()
    }
  }, [activeTab, sseConnected, fetchTasks])

  const handleRefresh = async () => {
    setLoading(true)
    try {
      await fetchTasks()
    } finally {
      setLoading(false)
    }
  }

  const handlePause = async (taskId: string) => {
    try {
      await pauseTask(taskId)
      toast.success(t('page.toastPause'))
    } finally {
      // 无需处理
    }
  }

  const handleResume = async (taskId: string) => {
    try {
      await resumeTask(taskId)
      toast.success(t('page.toastResume'))
    } finally {
      // 无需处理
    }
  }

  const handleCancel = async (taskId: string) => {
    try {
      await cancelTask(taskId)
      toast.success(t('page.toastCancel'))
    } finally {
      // 无需处理
    }
  }

  const handleRetry = async (taskId: string) => {
    try {
      await retryTask(taskId)
      toast.success(t('page.toastRetry'))
    } finally {
      // 无需处理
    }
  }

  const handleClearCompleted = async () => {
    if (completedTasks.length === 0) {
      toast.warning(t('page.toastClearWarn'))
      return
    }

    try {
      await clearCompletedTasks()
      toast.success(t('page.toastClearOk'))
    } finally {
      // 无需处理
    }
  }

  return (
    <div className='flex h-full flex-col'>
      {/* 顶部工具栏：窄屏放不下时自动换行 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        <div className='flex h-9 min-w-0 flex-1 items-center'>
          <h2 className='text-xl font-semibold tracking-tight'>
            {t('page.title')}
          </h2>
        </div>

        <Button
          variant='outline'
          size='icon'
          className='shrink-0'
          onClick={handleRefresh}
          aria-label={tc('refresh')}
        >
          <RefreshCw className='h-4 w-4' />
        </Button>

        {activeTab === 'completed' && completedTasks.length > 0 && (
          <Button
            variant='destructive'
            size='sm'
            className='shrink-0'
            onClick={handleClearCompleted}
          >
            <Trash2 className='mr-2 h-4 w-4' />
            {t('page.clearAll')}
          </Button>
        )}
      </div>

      {/* 标签页和操作按钮 */}
      <div className='flex flex-wrap items-center justify-between gap-x-3 gap-y-2 px-3 pt-2 pb-1.5 sm:px-6 sm:pt-2.5'>
        <Tabs
          value={activeTab}
          onValueChange={setActiveTab}
          className='min-w-0'
        >
          <TabsList>
            <TabsTrigger value='uploading'>
              {t('page.tabUploading')}{' '}
              {uploadingTasks.length > 0 && `(${uploadingTasks.length})`}
            </TabsTrigger>
            <TabsTrigger value='downloading'>
              {t('page.tabDownloading')}{' '}
              {downloadingTasks.length > 0 && `(${downloadingTasks.length})`}
            </TabsTrigger>
            <TabsTrigger value='completed'>
              {t('page.tabCompleted')}
            </TabsTrigger>
          </TabsList>
        </Tabs>

        {currentDisplayTasks.length > 0 && (
          <span className='text-sm text-muted-foreground'>
            {tc('listTotalItems', { count: currentDisplayTasks.length })}
          </span>
        )}
      </div>

      {/* 主内容区域 */}
      <div className='flex-1 overflow-auto px-3 pt-1 pb-3 sm:px-6 sm:pt-1.5 sm:pb-6'>
        {loading ? (
          <div className='flex h-full items-center justify-center'>
            <p className='text-muted-foreground'>{tc('loading')}</p>
          </div>
        ) : currentDisplayTasks.length === 0 ? (
          <div className='flex h-full items-center justify-center'>
            <Empty className='border-none'>
              <EmptyHeader>
                <EmptyMedia variant='icon'>
                  <Upload className='h-12 w-12' />
                </EmptyMedia>
                <EmptyTitle>
                  {activeTab === 'uploading'
                    ? t('page.emptyUploadingTitle')
                    : activeTab === 'downloading'
                      ? t('page.emptyDownloadingTitle')
                      : t('page.emptyCompletedTitle')}
                </EmptyTitle>
                <EmptyDescription>
                  {activeTab === 'uploading'
                    ? t('page.emptyUploadingDesc')
                    : activeTab === 'downloading'
                      ? t('page.emptyDownloadingDesc')
                      : t('page.emptyCompletedDesc')}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          </div>
        ) : (
          <>
            <TransferTable
              tasks={currentDisplayTasks}
              loading={loading}
              showActions={activeTab !== 'completed'}
              showCompleteTime={activeTab === 'completed'}
              onPause={handlePause}
              onResume={handleResume}
              onCancel={handleCancel}
              onRetry={handleRetry}
            />
            {/* 与文件列表同款的全量末尾提示；底部留白交给滚动区 padding，各页面保持一致 */}
            <p className='pt-6 text-center text-sm text-muted-foreground/55'>
              {t('page.noMore')}
            </p>
          </>
        )}
      </div>
    </div>
  )
}
