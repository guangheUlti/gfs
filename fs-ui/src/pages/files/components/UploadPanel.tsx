import { useState, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useTransferStore } from '@/store/transfer'
import { X, FileIcon, CheckCircle2, Pause, Play } from 'lucide-react'
import { Progress } from '@/components/ui/progress'
import { Button } from '@/components/ui/button'

const formatSpeed = (bytesPerSecond: number): string => {
  if (bytesPerSecond === 0) return '0 B/s'
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s']
  const k = 1024
  const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k))
  return `${(bytesPerSecond / Math.pow(k, i)).toFixed(2)} ${units[i]}`
}

const formatRemainingTime = (
  seconds: number,
  t: (key: string, options?: Record<string, unknown>) => string
): string => {
  if (seconds === 0 || !isFinite(seconds)) return '--'
  if (seconds < 60) return t('uploadPanel.sec', { n: Math.round(seconds) })
  if (seconds < 3600)
    return t('uploadPanel.min', { n: Math.round(seconds / 60) })
  return t('uploadPanel.hour', { n: Math.round(seconds / 3600) })
}

interface UploadPanelProps {
  onSuccess?: () => void
}

export default function UploadPanel({ onSuccess }: UploadPanelProps) {
  const { t } = useTranslation('files')
  const [showPanel, setShowPanel] = useState(false)
  const [isExpanded, setIsExpanded] = useState(false)
  const [prevCompletedCount, setPrevCompletedCount] = useState(0)

  const { getCurrentSessionTasks, pauseTask, resumeTask, cancelTask } =
    useTransferStore()
  const taskList = getCurrentSessionTasks()

  const isUploading = taskList.some((t) =>
    ['initialized', 'checking', 'uploading', 'merging'].includes(t.status)
  )
  const hasUploading = taskList.some((t) => t.status === 'uploading')
  const hasPaused = taskList.some((t) => t.status === 'paused')
  const hasActive = taskList.some((t) =>
    ['initialized', 'checking', 'uploading', 'paused'].includes(t.status)
  )

  const completedCount = taskList.filter((t) => t.status === 'completed').length
  const allCompleted =
    taskList.length > 0 &&
    taskList.every((t) =>
      ['completed', 'failed', 'cancelled'].includes(t.status)
    )

  // 整批进度：已完成文件计全量，其余按 uploadedBytes 累加
  const totalProgress = useMemo(() => {
    const totalBytes = taskList.reduce((sum, t) => sum + t.fileSize, 0)
    if (totalBytes === 0) return 0
    const uploadedBytes = taskList.reduce(
      (sum, t) => sum + (t.status === 'completed' ? t.fileSize : t.uploadedBytes),
      0
    )
    return (uploadedBytes / totalBytes) * 100
  }, [taskList])

  const summaryText = useMemo(
    () =>
      isUploading
        ? t('uploadPanel.uploading', {
            done: completedCount,
            total: taskList.length,
          })
        : t('uploadPanel.done', { count: taskList.length }),
    [isUploading, completedCount, taskList.length, t]
  )

  // 批量操作：暂停只作用于 uploading（状态机不允许其它状态直接转 paused），
  // 取消作用于所有未完成未失败的任务，已完成的文件保留
  const pauseAll = async () => {
    await Promise.allSettled(
      taskList
        .filter((t) => t.status === 'uploading')
        .map((t) => pauseTask(t.taskId))
    )
  }

  const resumeAll = async () => {
    await Promise.allSettled(
      taskList
        .filter((t) => t.status === 'paused')
        .map((t) => resumeTask(t.taskId))
    )
  }

  const cancelAll = async () => {
    await Promise.allSettled(
      taskList
        .filter((t) =>
          ['initialized', 'checking', 'uploading', 'paused'].includes(t.status)
        )
        .map((t) => cancelTask(t.taskId))
    )
  }

  // 任务级操作失败静默：store 已回滚状态，面板展示原状态即可
  const withSilentFail = (p: Promise<void>) => {
    p.catch(() => {})
  }

  // 显示面板：有任务时显示
  useEffect(() => {
    if (taskList.length > 0) {
      setShowPanel(true)
    }
  }, [taskList.length])

  // 自动关闭：所有任务完成后 3 秒自动关闭
  useEffect(() => {
    if (allCompleted && showPanel) {
      const timer = setTimeout(() => {
        setShowPanel(false)
      }, 3000)
      return () => clearTimeout(timer)
    }
  }, [allCompleted, showPanel])

  // 成功回调
  useEffect(() => {
    // 当完成数量增加时，调用 onSuccess 回调
    if (
      completedCount > prevCompletedCount &&
      completedCount > 0 &&
      onSuccess
    ) {
      onSuccess()
    }
    setPrevCompletedCount(completedCount)
  }, [completedCount, prevCompletedCount, onSuccess])

  if (!showPanel) return null

  return (
    <div className='fixed right-10 bottom-6 z-50 rounded-lg border bg-card shadow-2xl'>
      {!isExpanded ? (
        <div
          className='flex cursor-pointer items-center gap-2 px-4 py-3 hover:bg-accent'
          onClick={() => setIsExpanded(true)}
        >
          {isUploading ? (
            <div className='h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent' />
          ) : (
            <CheckCircle2 className='h-4 w-4 text-green-600' />
          )}
          <span className='text-sm font-medium'>{summaryText}</span>
          <X
            className='h-4 w-4 text-muted-foreground hover:text-foreground'
            onClick={(e) => {
              e.stopPropagation()
              setShowPanel(false)
            }}
          />
        </div>
      ) : (
        <div className='flex max-h-[400px] w-[360px] flex-col'>
          <div className='flex items-center justify-between border-b px-4 py-2'>
            <span className='min-w-0 truncate text-sm font-medium'>
              {summaryText}
            </span>
            <div className='flex flex-shrink-0 items-center gap-0.5'>
              {hasUploading && (
                <Button
                  variant='ghost'
                  size='sm'
                  className='h-7 gap-1 px-2'
                  onClick={pauseAll}
                >
                  <Pause className='h-3.5 w-3.5' />
                  {t('uploadPanel.pauseAll')}
                </Button>
              )}
              {hasPaused && (
                <Button
                  variant='ghost'
                  size='sm'
                  className='h-7 gap-1 px-2'
                  onClick={resumeAll}
                >
                  <Play className='h-3.5 w-3.5' />
                  {t('uploadPanel.resumeAll')}
                </Button>
              )}
              {hasActive && (
                <Button
                  variant='ghost'
                  size='sm'
                  className='text-destructive hover:bg-destructive/10 hover:text-destructive h-7 gap-1 px-2'
                  onClick={cancelAll}
                >
                  <X className='h-3.5 w-3.5' />
                  {t('uploadPanel.cancelAll')}
                </Button>
              )}
              <X
                className='h-4 w-4 cursor-pointer text-muted-foreground hover:text-foreground'
                onClick={() => setShowPanel(false)}
              />
            </div>
          </div>

          {/* 整批总进度：上传中或存在暂停任务时显示 */}
          {(isUploading || hasPaused) && (
            <div className='border-b px-4 py-2.5'>
              <Progress value={totalProgress} className='h-1' />
            </div>
          )}

          <div className='flex-1 overflow-y-auto p-2'>
            {taskList.length === 0 ? (
              <div className='py-8 text-center text-sm text-muted-foreground'>
                {t('uploadPanel.empty')}
              </div>
            ) : (
              taskList.map((task) => (
                <div
                  key={task.taskId}
                  className='flex items-start gap-3 rounded p-2 hover:bg-accent'
                >
                  <FileIcon className='mt-1 h-6 w-6 flex-shrink-0' />
                  <div className='min-w-0 flex-1'>
                    <div className='truncate text-sm font-medium'>
                      {task.fileName}
                    </div>

                    {/* Idle/Initialized */}
                    {(task.status === 'idle' ||
                      task.status === 'initialized') && (
                      <div className='mt-1 flex items-center gap-2'>
                        <div className='h-3 w-3 animate-spin rounded-full border-2 border-primary border-t-transparent' />
                        <span className='text-xs text-muted-foreground'>
                          {t('uploadPanel.preparing')}
                        </span>
                      </div>
                    )}

                    {/* Checking */}
                    {task.status === 'checking' && (
                      <div className='mt-1 flex items-center gap-2'>
                        <div className='h-3 w-3 animate-spin rounded-full border-2 border-primary border-t-transparent' />
                        <span className='text-xs text-muted-foreground'>
                          {t('uploadPanel.checking')}
                        </span>
                      </div>
                    )}

                    {/* Uploading */}
                    {task.status === 'uploading' && (
                      <div className='mt-1 space-y-1'>
                        <Progress value={task.progress} className='h-1' />
                        <div className='flex items-center justify-between text-xs'>
                          <span className='font-medium'>
                            {formatSpeed(task.speed)}
                          </span>
                          {task.remainingTime > 0 && (
                            <span className='text-muted-foreground'>
                              {t('uploadPanel.remaining', {
                                time: formatRemainingTime(
                                  task.remainingTime,
                                  t
                                ),
                              })}
                            </span>
                          )}
                        </div>
                      </div>
                    )}

                    {/* Paused */}
                    {task.status === 'paused' && (
                      <div className='mt-1 space-y-1'>
                        <Progress value={task.progress} className='h-1' />
                        <span className='text-xs text-muted-foreground'>
                          {t('uploadPanel.paused')}
                        </span>
                      </div>
                    )}

                    {/* Merging */}
                    {task.status === 'merging' && (
                      <div className='mt-1 flex items-center gap-2'>
                        <div className='h-3 w-3 animate-spin rounded-full border-2 border-primary border-t-transparent' />
                        <span className='text-xs text-muted-foreground'>
                          {t('uploadPanel.processing')}
                        </span>
                      </div>
                    )}

                    {/* Completed */}
                    {task.status === 'completed' && (
                      <span className='mt-1 block text-xs text-green-600'>
                        {t('uploadPanel.completed')}
                      </span>
                    )}

                    {/* Failed */}
                    {task.status === 'failed' && (
                      <span className='mt-1 block text-xs text-destructive'>
                        {task.errorMessage || t('uploadPanel.failed')}
                      </span>
                    )}

                    {/* Cancelled */}
                    {task.status === 'cancelled' && (
                      <span className='mt-1 block text-xs text-muted-foreground'>
                        {t('uploadPanel.cancelled')}
                      </span>
                    )}
                  </div>

                  {/* 行内操作：暂停/继续 + 取消 */}
                  <div className='flex flex-shrink-0 items-center gap-0.5'>
                    {task.status === 'uploading' && (
                      <Button
                        variant='ghost'
                        size='icon'
                        className='h-6 w-6'
                        onClick={() => withSilentFail(pauseTask(task.taskId))}
                      >
                        <Pause className='h-3.5 w-3.5' />
                      </Button>
                    )}
                    {task.status === 'paused' && (
                      <Button
                        variant='ghost'
                        size='icon'
                        className='h-6 w-6'
                        onClick={() => withSilentFail(resumeTask(task.taskId))}
                      >
                        <Play className='h-3.5 w-3.5' />
                      </Button>
                    )}
                    {['initialized', 'checking', 'uploading', 'paused'].includes(
                      task.status
                    ) && (
                      <Button
                        variant='ghost'
                        size='icon'
                        className='text-muted-foreground hover:text-destructive h-6 w-6'
                        onClick={() => withSilentFail(cancelTask(task.taskId))}
                      >
                        <X className='h-3.5 w-3.5' />
                      </Button>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>

          <div
            className='cursor-pointer border-t px-4 py-3 text-center text-sm hover:bg-accent'
            onClick={() => setIsExpanded(false)}
          >
            {t('uploadPanel.collapse')}
          </div>
        </div>
      )}
    </div>
  )
}
