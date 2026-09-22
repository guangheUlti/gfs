import { sseService } from '@/services/sse.service'
import { uploadExecutor } from '@/services/upload-executor'
import { downloadExecutor } from '@/services/download-executor'
import type {
  TransferTask,
  TaskStatus,
  ProgressUpdate,
  FileTransferTaskVO,
  SSEMessage,
} from '@/types/transfer'
import { toast } from 'sonner'
import { create } from 'zustand'
import {
  getTransferFiles,
  pauseUpload,
  resumeUpload,
  cancelUpload,
  initUpload,
  clearCompletedTasks as clearCompletedTasksApi,
} from '@/api/transfer'
import { createFolder } from '@/api/file'
import { UPLOAD_LIMITS, formatFileSize, shouldFilterFile } from '@/config/upload-limits'
import { progressCalculator } from '@/utils/progress-calculator'
import { stateMachine } from '@/utils/transfer-state-machine'
import {
  navigateToTransferIfEnabled,
  navigateBackFromAutoTransferIfIdle,
} from '@/services/auto-navigate'
import i18n from '@/i18n'
import type { FileItem } from '@/types/file'
import { useUserStore } from './user'

interface TransferStore {
  tasks: Map<string, TransferTask>
  sseConnected: boolean
  currentSessionId: string | null
  sessionTasks: Map<string, string[]>
  fileCache: Map<string, File>
  /** 等待空闲名额的上传任务 ID（FIFO）；同时上传数量=transferSetting.concurrentUploadQuantity */
  uploadQueue: string[]
  completedActionsTriggered: Set<string>
  errorNotificationTriggered: Set<string>
  /** 当前在传输页是否因「任务添加后自动跳转」进入（非手动切换），用于完成后自动跳回文件页 */
  autoArrivedOnTransfer: boolean
  setAutoArrivedOnTransfer: (value: boolean) => void

  // Getters
  getTaskList: () => TransferTask[]
  getUploadingTasks: () => TransferTask[]
  getDownloadingTasks: () => TransferTask[]
  getCompletedTasks: () => TransferTask[]
  getCurrentSessionTasks: () => TransferTask[]

  // Actions
  setSseConnected: (connected: boolean) => void
  transitionTo: (taskId: string, newStatus: TaskStatus) => boolean
  updateProgress: (taskId: string, data: ProgressUpdate) => void
  setTaskError: (taskId: string, errorMessage: string) => void
  handleSSEMessage: (message: SSEMessage) => void
  fetchTasks: () => Promise<void>
  syncTasks: () => Promise<void>
  startUploadSession: () => string
  createTask: (
    file: File,
    parentId?: string,
    sessionId?: string
  ) => Promise<string>
  createTasksWithDirectory: (
    files: File[],
    parentId?: string
  ) => Promise<void>
  createDownloadTasks: (files: FileItem[]) => Promise<void>
  pauseTask: (taskId: string) => Promise<void>
  resumeTask: (taskId: string) => Promise<void>
  cancelTask: (taskId: string) => Promise<void>
  retryTask: (taskId: string) => Promise<void>
  clearCompletedTasks: () => Promise<void>
  initSSE: (userId: string) => Promise<void>
  disconnectSSE: () => void
  getDisplayData: (taskId: string) => {
    progress: number
    speed: number
    remainingTime: number
  }

  // Internal methods
  triggerCompletedActions: (task: TransferTask) => void
  replaceDownloadTempTask: (
    tempId: string,
    realTaskId: string,
    meta: {
      fileName: string
      fileSize: number
      chunkSize: number
      totalChunks: number
      downloadedChunks: number[]
    }
  ) => void
  syncDownloadExecutorConfig: () => void
  ensureDownloadExecutorCallbacks: () => void
  pumpUploadQueue: () => void
  checkUnfinishedTasks: () => Promise<void>
  checkAndStartPolling: () => void
  startPolling: () => void
  stopPolling: () => void
  setupBeforeUnloadWarning: () => void
}

let sseMessageUnsubscribe: (() => void) | null = null
let sseConnectionUnsubscribe: (() => void) | null = null
let callbacksInitialized = false
let downloadCallbacksInitialized = false
let hasCheckedUnfinishedTasks = false
let pollingTimerId: number | null = null
let beforeUnloadWarningSetup = false
const POLLING_INTERVAL = 3000

function convertVOToTask(vo: FileTransferTaskVO): TransferTask {
  const now = Date.now()
  const progress = vo.progress ?? 0
  const formattedProgress = Math.round(progress)

  return {
    taskId: vo.taskId,
    taskType: vo.taskType ?? 'upload',
    fileName: vo.fileName,
    fileSize: vo.fileSize,
    status: vo.status as TaskStatus,
    progress: formattedProgress,
    uploadedBytes: vo.uploadedSize ?? 0,
    speed: vo.speed ?? 0,
    remainingTime: vo.remainTime ?? 0,
    errorMessage: vo.errorMsg,
    createdAt: vo.startTime ? new Date(vo.startTime).getTime() : now,
    updatedAt: now,
    parentId: vo.parentId,
    totalChunks: vo.totalChunks,
    uploadedChunks: vo.uploadedChunks,
    chunkSize: vo.chunkSize,
  }
}

export const useTransferStore = create<TransferStore>((set, get) => ({
  tasks: new Map(),
  sseConnected: false,
  currentSessionId: null,
  sessionTasks: new Map(),
  fileCache: new Map(),
  uploadQueue: [],
  completedActionsTriggered: new Set(),
  errorNotificationTriggered: new Set(),
  autoArrivedOnTransfer: false,
  setAutoArrivedOnTransfer: (value) => set({ autoArrivedOnTransfer: value }),

  getTaskList: () => Array.from(get().tasks.values()),

  getUploadingTasks: () =>
    get()
      .getTaskList()
      .filter(
        (task) =>
          task.taskType === 'upload' &&
          [
            'idle',
            'initialized',
            'checking',
            'uploading',
            'paused',
            'merging',
          ].includes(task.status)
      ),

  getDownloadingTasks: () =>
    get()
      .getTaskList()
      .filter(
        (task) =>
          task.taskType === 'download' &&
          ['idle', 'initialized', 'downloading', 'paused'].includes(
            task.status
          )
      ),

  getCompletedTasks: () =>
    get()
      .getTaskList()
      .filter((task) =>
        ['completed', 'failed', 'cancelled'].includes(task.status)
      ),

  getCurrentSessionTasks: () => {
    const { currentSessionId, sessionTasks, tasks } = get()
    if (!currentSessionId) return []
    const taskIds = sessionTasks.get(currentSessionId) || []
    return taskIds
      .map((id) => tasks.get(id))
      .filter((task): task is TransferTask => task !== undefined)
  },

  setSseConnected: (connected) => set({ sseConnected: connected }),

  transitionTo: (taskId, newStatus) => {
    const { tasks, completedActionsTriggered } = get()
    const task = tasks.get(taskId)
    if (!task) return false

    if (task.status === newStatus) {
      if (newStatus === 'completed' && !completedActionsTriggered.has(taskId)) {
        get().triggerCompletedActions(task)
      }
      return true
    }

    const updatedTask = stateMachine.transition(task, newStatus)
    if (updatedTask) {
      const newTasks = new Map(tasks)
      newTasks.set(taskId, updatedTask)
      set({ tasks: newTasks })

      if (newStatus === 'completed' && !completedActionsTriggered.has(taskId)) {
        get().triggerCompletedActions(updatedTask)
      }

      // 上传任务到达终态后释放名额，让队列里排队的任务补上
      if (
        ['completed', 'failed', 'cancelled'].includes(newStatus) &&
        updatedTask.taskType === 'upload'
      ) {
        get().pumpUploadQueue()
      }

      get().checkAndStartPolling()
      return true
    }

    return false
  },

  triggerCompletedActions: (task: TransferTask) => {
    const { completedActionsTriggered, fileCache } = get()
    completedActionsTriggered.add(task.taskId)

    if (task.taskType === 'download') {
      toast.success(
        i18n.t('transfer:page.toastDownloadComplete', { name: task.fileName })
      )
      progressCalculator.clear(task.taskId)
      navigateBackFromAutoTransferIfIdle()
      return
    }

    toast.success(
      i18n.t('files:uploadPanel.toastFileComplete', { name: task.fileName })
    )

    window.dispatchEvent(
      new CustomEvent('file-upload-complete', {
        detail: { parentId: task.parentId },
      })
    )

    progressCalculator.clear(task.taskId)
    fileCache.delete(task.taskId)

    if (uploadExecutor.getTaskContext(task.taskId)) {
      uploadExecutor.cancel(task.taskId)
    }
    navigateBackFromAutoTransferIfIdle()
  },

  setTaskError: (taskId, errorMessage) => {
    const { tasks, errorNotificationTriggered } = get()
    const task = tasks.get(taskId)
    if (task) {
      get().transitionTo(taskId, 'failed')
      const updatedTask = tasks.get(taskId)
      if (updatedTask) {
        const newTasks = new Map(tasks)
        newTasks.set(taskId, { ...updatedTask, errorMessage })
        set({ tasks: newTasks })
      }

      if (!errorNotificationTriggered.has(taskId)) {
        errorNotificationTriggered.add(taskId)
        toast.error(
          i18n.t('files:uploadPanel.toastFileFailed', {
            name: task.fileName,
            message: errorMessage,
          })
        )
      }
    }
  },

  updateProgress: (taskId, data) => {
    const { tasks } = get()
    const task = tasks.get(taskId)
    if (!task) return

    const shouldUpdate = progressCalculator.update(
      taskId,
      data.uploadedBytes,
      data.totalBytes
    )

    if (shouldUpdate) {
      const displayData = progressCalculator.getDisplayData(taskId)

      const updatedTask: TransferTask = {
        ...task,
        uploadedBytes: data.uploadedBytes,
        progress: displayData.progress,
        speed: displayData.speed,
        remainingTime: displayData.remainingTime,
        updatedAt: Date.now(),
      }

      if (data.uploadedChunks !== undefined) {
        updatedTask.uploadedChunks = data.uploadedChunks
      }
      if (data.totalChunks !== undefined) {
        updatedTask.totalChunks = data.totalChunks
      }

      const newTasks = new Map(tasks)
      newTasks.set(taskId, updatedTask)
      set({ tasks: newTasks })
    }
  },

  handleSSEMessage: (message) => {
    const { type, taskId, data } = message

    // 已取消任务的迟到事件一律忽略：后端取消即删记录，但取消竞态下
    // SSE 可能仍推送 complete/error（如合并竞态完成、分片报错），
    // 采信会把任务从「已取消」错误翻转成「已完成/失败」并弹误导 toast
    const sseTask = get().tasks.get(taskId)
    if (sseTask?.status === 'cancelled') return

    switch (type) {
      case 'progress': {
        const progressData = data as any
        get().updateProgress(taskId, {
          uploadedBytes: progressData.uploadedBytes,
          totalBytes: progressData.totalBytes,
          uploadedChunks: progressData.uploadedChunks,
          totalChunks: progressData.totalChunks,
        })
        break
      }

      case 'status': {
        const statusData = data as any
        if (statusData.status) {
          get().transitionTo(taskId, statusData.status)
        }
        break
      }

      case 'complete': {
        // 下载任务的完成由执行器在本地保存成功后再通知，
        // 后端标记完分片就会推 complete，若直接采信会提前展示已完成
        const task = get().tasks.get(taskId)
        if (task?.taskType === 'download' && downloadExecutor.getTaskContext(taskId)) {
          break
        }
        get().transitionTo(taskId, 'completed')
        break
      }

      case 'error': {
        const errorData = data as any
        const errorMessage = errorData.message || '上传失败'
        // 取消竞态下后端记录已删，报「任务不存在」属预期，不弹错误
        if (errorMessage.includes('任务不存在')) break
        get().setTaskError(taskId, errorMessage)
        break
      }

      default:
        break
    }
  },

  fetchTasks: async () => {
    try {
      const taskVOs = await getTransferFiles()

      const newTasks = new Map<string, TransferTask>()
      progressCalculator.clearAll()

      // 保留尚未在后端登记的排队占位任务（下载并发队列）
      get()
        .getTaskList()
        .forEach((task) => {
          if (
            task.taskType === 'download' &&
            downloadExecutor.isQueued(task.taskId)
          ) {
            newTasks.set(task.taskId, task)
          }
        })

      // 确保 taskVOs 是数组
      const tasks = Array.isArray(taskVOs) ? taskVOs : []

      tasks.forEach((vo) => {
        const task = convertVOToTask(vo)
        newTasks.set(task.taskId, task)
      })

      set({ tasks: newTasks })

      await get().checkUnfinishedTasks()
    } catch (error) {
      console.error('获取传输任务列表失败:', error)
      // 静默失败，不抛出错误
    }
  },

  checkUnfinishedTasks: async () => {
    if (hasCheckedUnfinishedTasks) return
    hasCheckedUnfinishedTasks = true

    const { tasks } = get()
    const unfinishedTasks = Array.from(tasks.values()).filter(
      (task) =>
        task.status === 'idle' ||
        task.status === 'initialized' ||
        task.status === 'uploading' ||
        task.status === 'checking' ||
        task.status === 'downloading' ||
        task.status === 'paused' ||
        task.status === 'merging'
    )

    if (unfinishedTasks.length === 0) return

    const results = await Promise.allSettled(
      unfinishedTasks.map(
        async (
          task
        ): Promise<{
          success: boolean
          taskId: string
          resumed: boolean
          error?: unknown
        }> => {
          try {
            if (task.taskType === 'download') {
              // 当前会话排队中的占位任务，尚未开始下载，无需处理
              if (downloadExecutor.isQueued(task.taskId)) {
                return { success: true, taskId: task.taskId, resumed: false }
              }

              // 本地留有分片临时文件才续传，否则取消（避免拼出损坏文件）
              const resumable =
                !!task.chunkSize &&
                !!task.totalChunks &&
                (await downloadExecutor.hasLocalProgress(task.taskId))

              if (resumable) {
                get().syncDownloadExecutorConfig()
                get().ensureDownloadExecutorCallbacks()
                if (task.status === 'paused') {
                  await resumeUpload(task.taskId).catch(() => undefined)
                }
                get().transitionTo(task.taskId, 'downloading')
                await downloadExecutor.adoptResumed({
                  taskId: task.taskId,
                  fileName: task.fileName,
                  fileSize: task.fileSize,
                  chunkSize: task.chunkSize,
                  totalChunks: task.totalChunks,
                  chunkConcurrency: useUserStore.getState().transferSetting
                    ?.downloadSpeedLimit
                  ? 1
                  : 3,
                })
                return { success: true, taskId: task.taskId, resumed: true }
              }

              await cancelUpload(task.taskId).catch((error: any) => {
                const message = `${error?.message ?? ''}${error?.response?.data?.message ?? ''}`
                if (!message.includes('任务不存在')) throw error
              })
              get().transitionTo(task.taskId, 'cancelled')
              return { success: true, taskId: task.taskId, resumed: false }
          }

          await cancelUpload(task.taskId)
          get().transitionTo(task.taskId, 'cancelled')
          return { success: true, taskId: task.taskId, resumed: false }
        } catch (error: any) {
          // 如果任务不存在，也算成功（因为目标已达成）
          if (
            error?.message?.includes('任务不存在') ||
            error?.response?.data?.message?.includes('任务不存在')
          ) {
            get().transitionTo(task.taskId, 'cancelled')
            return { success: true, taskId: task.taskId, resumed: false }
          }
          console.error('取消任务失败:', task.taskId, error)
          return { success: false, taskId: task.taskId, error }
        }
      })
    )

    const settled = results.filter(
      (r) => r.status === 'fulfilled' && r.value.success
    )
    const resumedCount = settled.filter((r) => r.value.resumed).length
    const failCount = results.length - settled.length

    // 按任务类型分别统计被取消的任务数，提示文案区分上传/下载
    const settledTaskIds = new Set(
      settled
        .filter((r) => !r.value.resumed)
        .map((r) => r.value.taskId)
    )
    const cancelledUploads = unfinishedTasks.filter(
      (task) =>
        task.taskType === 'upload' && settledTaskIds.has(task.taskId)
    ).length
    const cancelledDownloads =
      settled.length - resumedCount - cancelledUploads

    if (resumedCount > 0) {
      toast.info(`已恢复 ${resumedCount} 个未完成的下载任务`)
    }

    const cancelledParts: string[] = []
    if (cancelledUploads > 0) cancelledParts.push(`${cancelledUploads} 个上传`)
    if (cancelledDownloads > 0)
      cancelledParts.push(`${cancelledDownloads} 个下载`)

    if (cancelledParts.length > 0) {
      const description =
        failCount > 0
          ? `${failCount} 个任务取消失败`
          : '刷新页面会中断进行中的传输任务，建议等待传输完成后再刷新'
      toast.info(`已自动取消 ${cancelledParts.join('、')}任务`, {
        description,
      })
    }
  },

  syncTasks: async () => {
    try {
      const taskVOs = await getTransferFiles()
      const { tasks } = get()

      const newTasks = new Map(tasks)

      // 确保 taskVOs 是数组
      const taskList = Array.isArray(taskVOs) ? taskVOs : []

      taskList.forEach((vo) => {
        const existingTask = newTasks.get(vo.taskId)
        const newTask = convertVOToTask(vo)

        // 本地已取消的任务不被后端状态覆盖：后端记录删除前推送的
        // 竞态状态（如 completed）不应推翻用户的取消操作
        if (existingTask?.status === 'cancelled') return

        if (!existingTask) {
          newTasks.set(vo.taskId, newTask)
        } else {
          const statePriority: Record<TaskStatus, number> = {
            idle: 0,
            initialized: 1,
            checking: 2,
            paused: 3,
            uploading: 4,
            downloading: 4,
            merging: 5,
            cancelled: 6,
            failed: 7,
            completed: 8,
          }

          const existingPriority = statePriority[existingTask.status] || 0
          const newPriority = statePriority[newTask.status] || 0

          if (
            newPriority > existingPriority ||
            newTask.status === 'completed' ||
            newTask.status === 'failed' ||
            newTask.status === 'cancelled'
          ) {
            newTasks.set(vo.taskId, newTask)
          } else {
            newTasks.set(vo.taskId, {
              ...existingTask,
              uploadedBytes: newTask.uploadedBytes,
              uploadedChunks: newTask.uploadedChunks,
              totalChunks: newTask.totalChunks,
            })
          }
        }
      })

      const backendTaskIds = new Set(taskList.map((vo) => vo.taskId))
      newTasks.forEach((task, taskId) => {
        if (!backendTaskIds.has(taskId)) {
          // 排队占位任务后端尚不存在，保留
          if (task.taskType === 'download' && downloadExecutor.isQueued(taskId)) {
            return
          }
          // 已取消任务后端记录已删属预期，静默移除即可
          if (task.status === 'cancelled') {
            newTasks.delete(taskId)
            progressCalculator.clear(taskId)
            return
          }
          // 活跃任务从后端列表消失，说明被外部删除（如另一端操作），
          // 置为取消而非继续显示假进度
          if (
            task.taskType === 'upload' &&
            ['checking', 'uploading', 'merging', 'initialized'].includes(
              task.status
            )
          ) {
            get().transitionTo(taskId, 'cancelled')
          }
          newTasks.delete(taskId)
          progressCalculator.clear(taskId)
        }
      })

      set({ tasks: newTasks })
    } catch (error) {
      console.error('同步传输任务失败:', error)
      // 静默失败，不抛出错误
    }

    // 同步可能把任务直接置为终态（绕过 transitionTo），补偿泵一次上传队列
    get().pumpUploadQueue()
  },

  startUploadSession: () => {
    const sessionId = `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
    const { sessionTasks } = get()
    const newSessionTasks = new Map(sessionTasks)
    newSessionTasks.set(sessionId, [])
    set({ currentSessionId: sessionId, sessionTasks: newSessionTasks })
    return sessionId
  },

  createTask: async (file, parentId, sessionId) => {
    // 从用户 store 获取传输设置
    const userStore = useUserStore.getState()
    if (!userStore.transferSetting) {
      await userStore.loadTransferSetting()
    }

    const settings = userStore.transferSetting!
    const chunkSize = settings.chunkSize

    if (!callbacksInitialized) {
      callbacksInitialized = true
      uploadExecutor.setCallbacks({
        onTransition: (taskId, status) => {
          get().transitionTo(taskId, status as TaskStatus)
        },
        onProgress: (taskId, data) => {
          get().updateProgress(taskId, data)
        },
        onError: (taskId, errorMessage) => {
          get().setTaskError(taskId, errorMessage)
        },
      })
    }

    const totalChunks = Math.ceil(file.size / chunkSize)

    const taskId = await initUpload({
      fileName: file.name,
      fileSize: file.size,
      parentId,
      totalChunks,
      chunkSize,
      mimeType: file.type || 'application/octet-stream',
    })

    const now = Date.now()

    const task: TransferTask = {
      taskId,
      taskType: 'upload',
      fileName: file.name,
      fileSize: file.size,
      status: 'idle',
      progress: 0,
      uploadedBytes: 0,
      speed: 0,
      remainingTime: 0,
      createdAt: now,
      updatedAt: now,
      parentId,
      mimeType: file.type || 'application/octet-stream',
      totalChunks,
      uploadedChunks: 0,
      chunkSize,
    }

    const { tasks, fileCache, sessionTasks, currentSessionId } = get()
    const newTasks = new Map(tasks)
    newTasks.set(taskId, task)

    const newFileCache = new Map(fileCache)
    newFileCache.set(taskId, file)

    const targetSessionId = sessionId || currentSessionId
    const newSessionTasks = new Map(sessionTasks)
    if (targetSessionId) {
      const sessionTaskList = newSessionTasks.get(targetSessionId) || []
      sessionTaskList.push(taskId)
      newSessionTasks.set(targetSessionId, sessionTaskList)
    }

    set({
      tasks: newTasks,
      fileCache: newFileCache,
      sessionTasks: newSessionTasks,
    })

    get().transitionTo(taskId, 'initialized')

    set({ uploadQueue: [...get().uploadQueue, taskId] })
    get().pumpUploadQueue()

    return taskId
  },

  createTasksWithDirectory: async (files, parentId) => {
    interface FileWithPath {
      webkitRelativePath?: string
    }

    const filesWithPath = files as (File & FileWithPath)[]

    // ========== 限制检查 ==========
    
    // 1. 总大小检查
    const totalSize = filesWithPath.reduce((sum, file) => sum + file.size, 0)
    if (totalSize > UPLOAD_LIMITS.MAX_TOTAL_SIZE) {
      const currentSize = formatFileSize(totalSize)
      const maxSize = formatFileSize(UPLOAD_LIMITS.MAX_TOTAL_SIZE)
      toast.error(`单次上传总大小不能超过 ${maxSize}，当前为 ${currentSize}`, {
        description: '建议分批上传或使用客户端'
      })
      throw new Error('总大小超限')
    }

    // 2. 目录深度检查
    let maxDepth = 0
    let deepestPath = ''
    filesWithPath.forEach((file) => {
      const path = file.webkitRelativePath || file.name
      const depth = path.split('/').length - 1
      if (depth > maxDepth) {
        maxDepth = depth
        deepestPath = path
      }
    })
    if (maxDepth > UPLOAD_LIMITS.MAX_DEPTH) {
      toast.error(`目录层级不能超过 ${UPLOAD_LIMITS.MAX_DEPTH} 层，当前为 ${maxDepth} 层`, {
        description: `最深路径: ${deepestPath}`
      })
      throw new Error('目录深度超限')
    }

    // 3. 文件名长度检查
    const invalidFiles = filesWithPath.filter((file) => {
      const path = file.webkitRelativePath || file.name
      return file.name.length > UPLOAD_LIMITS.MAX_FILENAME_LENGTH || 
             path.length > UPLOAD_LIMITS.MAX_PATH_LENGTH
    })
    if (invalidFiles.length > 0) {
      const example = invalidFiles[0].name
      toast.error('存在文件名或路径过长的文件', {
        description: `如: ${example.substring(0, 50)}...`
      })
      throw new Error('文件名或路径过长')
    }

    // 4. 自动过滤系统文件
    const filteredFiles = filesWithPath.filter((file) => {
      const path = file.webkitRelativePath || file.name
      return !shouldFilterFile(path)
    })

    if (filteredFiles.length < filesWithPath.length) {
      const filteredCount = filesWithPath.length - filteredFiles.length
      toast.info(`已自动过滤 ${filteredCount} 个系统文件`)
    }

    if (filteredFiles.length === 0) {
      toast.warning('没有可上传的文件')
      return
    }

    // ========== 解析目录结构 ==========
    
    const dirMap = new Map<string, string>() // path -> folderId
    const filesByDir = new Map<string, File[]>() // dirPath -> files

    // 收集所有目录路径
    const allDirs = new Set<string>()
    filteredFiles.forEach((file) => {
      const relativePath = file.webkitRelativePath || file.name
      const pathParts = relativePath.split('/')
      
      // 如果有多级目录
      if (pathParts.length > 1) {
        let currentPath = ''
        // 遍历除了文件名之外的所有部分
        for (let i = 0; i < pathParts.length - 1; i++) {
          currentPath += (i > 0 ? '/' : '') + pathParts[i]
          allDirs.add(currentPath)
        }
        
        // 记录文件所属目录
        const dirPath = pathParts.slice(0, -1).join('/')
        if (!filesByDir.has(dirPath)) {
          filesByDir.set(dirPath, [])
        }
        filesByDir.get(dirPath)!.push(file)
      } else {
        // 根目录文件
        if (!filesByDir.has('')) {
          filesByDir.set('', [])
        }
        filesByDir.get('')!.push(file)
      }
    })

    // 按层级排序目录（确保父目录先创建）
    const sortedDirs = Array.from(allDirs).sort((a, b) => {
      const aDepth = a.split('/').length
      const bDepth = b.split('/').length
      return aDepth - bDepth
    })

    // 显示上传信息
    toast.info(`准备上传 ${filteredFiles.length} 个文件，共 ${sortedDirs.length} 个文件夹`, {
      description: `总大小: ${formatFileSize(totalSize)}`
    })

    // ========== 递归创建目录 ==========
    
    try {
      for (const dirPath of sortedDirs) {
        const pathParts = dirPath.split('/')
        const folderName = pathParts[pathParts.length - 1]
        const parentPath = pathParts.slice(0, -1).join('/')
        
        // 获取父目录 ID
        const parentFolderId = parentPath ? dirMap.get(parentPath) : parentId

        try {
          // 调用创建文件夹 API
          const result = await createFolder({
            folderName,
            parentId: parentFolderId,
          })
          
          // 保存文件夹 ID
          if (result?.id) {
            dirMap.set(dirPath, result.id)
          } else {
            throw new Error('创建文件夹成功但未返回 ID')
          }
        } catch (error) {
          console.error(`创建文件夹失败: ${dirPath}`, error)
          toast.error(`创建文件夹失败: ${folderName}`)
          throw error
        }
      }

      // ========== 上传所有文件 ==========
      
      const uploadPromises: Promise<string>[] = []
      
      filesByDir.forEach((dirFiles, dirPath) => {
        const targetParentId = dirPath ? dirMap.get(dirPath) : parentId
        
        dirFiles.forEach((file) => {
          uploadPromises.push(get().createTask(file, targetParentId))
        })
      })

      await Promise.all(uploadPromises)

      toast.success(`已添加 ${filteredFiles.length} 个文件到上传队列`)
    } catch (error) {
      console.error('上传目录失败:', error)
      throw error
    }
  },

  createDownloadTasks: async (files) => {
    const downloadable = files.filter((file) => !file.isDir)
    const skippedDirs = files.length - downloadable.length
    if (skippedDirs > 0) {
      toast.info(
        i18n.t('transfer:page.folderSkipHint', { count: skippedDirs })
      )
    }
    if (downloadable.length === 0) return

    const userStore = useUserStore.getState()
    if (!userStore.transferSetting) {
      await userStore.loadTransferSetting()
    }
    get().syncDownloadExecutorConfig()
    get().ensureDownloadExecutorCallbacks()

    const { tasks, sessionTasks, currentSessionId } = get()
    const newTasks = new Map(tasks)
    const newSessionTasks = new Map(sessionTasks)
    const now = Date.now()

    downloadable.forEach((file) => {
      const tempId = downloadExecutor.enqueue({
        fileId: file.id,
        fileName: file.displayName,
        fileSize: file.size,
      })

      newTasks.set(tempId, {
        taskId: tempId,
        taskType: 'download',
        fileName: file.displayName,
        fileSize: file.size,
        status: 'initialized',
        progress: 0,
        uploadedBytes: 0,
        speed: 0,
        remainingTime: 0,
        createdAt: now,
        updatedAt: now,
      })

      if (currentSessionId) {
        const list = newSessionTasks.get(currentSessionId) || []
        list.push(tempId)
        newSessionTasks.set(currentSessionId, list)
      }
    })

    set({ tasks: newTasks, sessionTasks: newSessionTasks })

    toast.success(
      i18n.t('transfer:page.toastDownloadQueued', {
        count: downloadable.length,
      })
    )

    // 传输设置开了「自动跳转」时前往传输页（下载任务直达「下载中」tab）；否则留在文件页。
    // 例外：本次添加的任务全部走流式直下（浏览器原生落盘、页面内无进度可看）时，
    // 跳过去只会看到瞬间完成的假状态再被弹回，不如留在文件页
    const hasTrackableDownload = downloadable.some(
      (file) => !downloadExecutor.willUseNativeDownload(file.size)
    )
    if (hasTrackableDownload) {
      navigateToTransferIfEnabled('downloading')
    }
  },

  replaceDownloadTempTask: (tempId, realTaskId, meta) => {
    const { tasks, sessionTasks, currentSessionId } = get()
    const oldTask = tasks.get(tempId)

    const newTasks = new Map(tasks)
    newTasks.delete(tempId)
    newTasks.set(realTaskId, {
      ...(oldTask ?? {}),
      taskId: realTaskId,
      taskType: 'download',
      fileName: meta.fileName,
      fileSize: meta.fileSize,
      chunkSize: meta.chunkSize,
      totalChunks: meta.totalChunks,
      uploadedChunks: meta.downloadedChunks.length,
      status: 'downloading',
      updatedAt: Date.now(),
    } as TransferTask)

    const newSessionTasks = new Map(sessionTasks)
    if (currentSessionId) {
      const list = newSessionTasks.get(currentSessionId)
      if (list) {
        newSessionTasks.set(
          currentSessionId,
          list.map((id) => (id === tempId ? realTaskId : id))
        )
      }
    }

    set({ tasks: newTasks, sessionTasks: newSessionTasks })
  },

  syncDownloadExecutorConfig: () => {
    const settings = useUserStore.getState().transferSetting
    downloadExecutor.configure(
      settings?.concurrentDownloadQuantity || 1,
      (settings?.downloadSpeedLimit ?? -1) > 0
    )
  },

  ensureDownloadExecutorCallbacks: () => {
    if (downloadCallbacksInitialized) return
    downloadCallbacksInitialized = true
    downloadExecutor.setCallbacks({
      onTransition: (taskId, status) => {
        get().transitionTo(taskId, status as TaskStatus)
      },
      onProgress: (taskId, data) => {
        get().updateProgress(taskId, data)
      },
      onError: (taskId, errorMessage) => {
        get().setTaskError(taskId, errorMessage)
      },
      onTaskIdReplaced: (tempId, realTaskId, meta) => {
        get().replaceDownloadTempTask(tempId, realTaskId, meta)
      },
    })
  },

  /** 从队列取出名额内的上传任务开始执行；任务终态/取消/入队后都会调用 */
  pumpUploadQueue: () => {
    const limit = Math.max(
      1,
      useUserStore.getState().transferSetting?.concurrentUploadQuantity || 1
    )

    for (;;) {
      const { uploadQueue, tasks, fileCache } = get()
      if (uploadQueue.length === 0) return

      const activeCount = Array.from(tasks.values()).filter(
        (task) =>
          task.taskType === 'upload' &&
          ['checking', 'uploading', 'merging'].includes(task.status)
      ).length
      if (activeCount >= limit) return

      const taskId = uploadQueue[0]
      const file = fileCache.get(taskId)
      const task = tasks.get(taskId)

      set({ uploadQueue: uploadQueue.slice(1) })

      // 任务已被取消或文件缓存丢失：跳过，不占用本次名额
      if (!file || !task) continue
      if (
        ['completed', 'failed', 'cancelled', 'paused'].includes(task.status)
      ) {
        continue
      }

      // start() 会在首个 await 前同步转入 checking，循环按最新活跃数继续判断
      const settings = useUserStore.getState().transferSetting
      uploadExecutor
        .start(
          taskId,
          file,
          uploadExecutor.DEFAULT_CONCURRENCY,
          settings?.chunkSize || 5 * 1024 * 1024
        )
        .catch(() => {
          // Silent
        })
    }
  },

  pauseTask: async (taskId) => {
    const { tasks } = get()
    const task = tasks.get(taskId)
    if (!task) throw new Error(`Task not found: ${taskId}`)

    if (task.taskType === 'download') {
      if (!downloadExecutor.pause(taskId)) {
        // 排队占位任务尚未开始，直接转取消
        get().transitionTo(taskId, 'cancelled')
        return
      }
      if (!get().transitionTo(taskId, 'paused')) {
        throw new Error(`Cannot pause task in status: ${task.status}`)
      }
      try {
        await pauseUpload(taskId)
      } catch (error) {
        get().transitionTo(taskId, task.status)
        throw error
      }
      return
    }

    uploadExecutor.pause(taskId)

    if (!get().transitionTo(taskId, 'paused')) {
      throw new Error(`Cannot pause task in status: ${task.status}`)
    }

    try {
      await pauseUpload(taskId)
    } catch (error) {
      get().transitionTo(taskId, task.status)
      throw error
    }
  },

  resumeTask: async (taskId) => {
    const { tasks } = get()
    const task = tasks.get(taskId)
    if (!task) throw new Error(`Task not found: ${taskId}`)

    const targetStatus = task.taskType === 'download' ? 'downloading' : 'uploading'
    if (!get().transitionTo(taskId, targetStatus)) {
      throw new Error(`Cannot resume task in status: ${task.status}`)
    }

    try {
      await resumeUpload(taskId)
      if (task.taskType === 'download') {
        downloadExecutor.resume(taskId).catch(() => {
          // Silent
        })
      } else {
        uploadExecutor.resume(taskId).catch(() => {
          // Silent
        })
      }
    } catch (error) {
      get().transitionTo(taskId, task.status)
      throw error
    }
  },

  cancelTask: async (taskId) => {
    const { tasks } = get()
    const task = tasks.get(taskId)
    if (!task) throw new Error(`Task not found: ${taskId}`)

    if (task.taskType === 'download') {
      downloadExecutor.cancel(taskId)

      if (!get().transitionTo(taskId, 'cancelled')) {
        throw new Error(`Cannot cancel task in status: ${task.status}`)
      }

      try {
        await cancelUpload(taskId)
      } catch (error) {
        // 排队占位任务后端尚不存在，视为取消成功
        const message = `${error?.message ?? ''}${error?.response?.data?.message ?? ''}`
        if (!message.includes('任务不存在')) {
          get().transitionTo(taskId, task.status)
          throw error
        }
      }
      progressCalculator.clear(taskId)
      return
    }

    uploadExecutor.cancel(taskId)

    // 任务已被竞态事件（后端合并完成等）置为 completed 时无需再取消
    if (task.status === 'completed') {
      progressCalculator.clear(taskId)
      return
    }

    if (!get().transitionTo(taskId, 'cancelled')) {
      throw new Error(`Cannot cancel task in status: ${task.status}`)
    }

    try {
      await cancelUpload(taskId)
      progressCalculator.clear(taskId)
      const { fileCache } = get()
      const newFileCache = new Map(fileCache)
      newFileCache.delete(taskId)
      set({ fileCache: newFileCache })
    } catch (error) {
      // 后端已无此任务（重复取消/记录已被删）：目标已达成，不回滚不报错
      const message = `${error?.message ?? ''}${error?.response?.data?.message ?? ''}`
      if (message.includes('任务不存在')) {
        progressCalculator.clear(taskId)
        return
      }
      get().transitionTo(taskId, task.status)
      throw error
    }
  },

  retryTask: async (taskId) => {
    const {
      tasks,
      fileCache,
      completedActionsTriggered,
      errorNotificationTriggered,
    } = get()
    const task = tasks.get(taskId)
    if (!task) throw new Error(`Task not found: ${taskId}`)

    if (task.status !== 'failed') {
      throw new Error(`Cannot retry task in status: ${task.status}`)
    }

    if (task.taskType === 'download') {
      progressCalculator.reset(taskId)
      completedActionsTriggered.delete(taskId)
      errorNotificationTriggered.delete(taskId)

      if (!get().transitionTo(taskId, 'initialized')) {
        throw new Error('Failed to transition task to initialized state')
      }

      get().syncDownloadExecutorConfig()

      try {
        get().transitionTo(taskId, 'downloading')
        if (downloadExecutor.getTaskContext(taskId)) {
          await downloadExecutor.retry(taskId)
        } else {
          if (!task.chunkSize || !task.totalChunks) {
            throw new Error('任务信息不完整，无法重试')
          }
          await downloadExecutor.adoptResumed({
            taskId,
            fileName: task.fileName,
            fileSize: task.fileSize,
            chunkSize: task.chunkSize,
            totalChunks: task.totalChunks,
            chunkConcurrency: useUserStore.getState().transferSetting
              ?.downloadSpeedLimit
            ? 1
              : 3,
          })
        }
      } catch (error) {
        get().setTaskError(
          taskId,
          error instanceof Error ? error.message : '重试失败'
        )
      }
      return
    }

    if (!fileCache.get(taskId)) {
      throw new Error('File not found in cache, cannot retry')
    }

    progressCalculator.reset(taskId)
    completedActionsTriggered.delete(taskId)
    errorNotificationTriggered.delete(taskId)

    if (!get().transitionTo(taskId, 'initialized')) {
      throw new Error('Failed to transition task to initialized state')
    }

    const updatedTask = tasks.get(taskId)
    if (updatedTask) {
      const newTasks = new Map(tasks)
      newTasks.set(taskId, {
        ...updatedTask,
        progress: 0,
        uploadedBytes: 0,
        uploadedChunks: 0,
        speed: 0,
        remainingTime: 0,
        errorMessage: undefined,
      })
      set({ tasks: newTasks })
    }

    set({ uploadQueue: [...get().uploadQueue, taskId] })
    get().pumpUploadQueue()
  },

  clearCompletedTasks: async () => {
    try {
      await clearCompletedTasksApi()

      const { tasks } = get()
      const newTasks = new Map(tasks)

      // 删除所有已完成、失败和取消的任务
      Array.from(newTasks.values()).forEach((task) => {
        if (['completed', 'failed', 'cancelled'].includes(task.status)) {
          newTasks.delete(task.taskId)
          progressCalculator.clear(task.taskId)
        }
      })

      set({ tasks: newTasks })
    } catch (error) {
      console.error('清空已完成任务失败:', error)
      throw error
    }
  },

  initSSE: async (userId: string) => {
    try {
      await get().fetchTasks()

      sseService.setReconnectSyncCallback(async () => {
        await get().syncTasks()
      })

      if (sseMessageUnsubscribe) {
        sseMessageUnsubscribe()
      }
      sseMessageUnsubscribe = sseService.onMessage(get().handleSSEMessage)

      if (sseConnectionUnsubscribe) {
        sseConnectionUnsubscribe()
      }
      sseConnectionUnsubscribe = sseService.onConnectionChange((connected) => {
        get().setSseConnected(connected)
      })

      sseService.connect(userId)

      get().checkAndStartPolling()
      get().setupBeforeUnloadWarning()
    } catch (error) {
      console.error('初始化 SSE 失败:', error)
    }
  },

  disconnectSSE: () => {
    if (sseMessageUnsubscribe) {
      sseMessageUnsubscribe()
      sseMessageUnsubscribe = null
    }

    if (sseConnectionUnsubscribe) {
      sseConnectionUnsubscribe()
      sseConnectionUnsubscribe = null
    }

    sseService.disconnect()
    get().setSseConnected(false)
    get().stopPolling()
  },

  checkAndStartPolling: () => {
    const { tasks } = get()
    const hasActiveTasks = Array.from(tasks.values()).some(
      (task) =>
        task.status === 'uploading' ||
        task.status === 'downloading' ||
        task.status === 'checking' ||
        task.status === 'merging'
    )

    if (hasActiveTasks && pollingTimerId === null) {
      get().startPolling()
    }
  },

  startPolling: () => {
    if (pollingTimerId !== null) return

    pollingTimerId = window.setInterval(async () => {
      const { tasks } = get()
      const activeTasks = Array.from(tasks.values()).filter(
        (task) =>
          task.status === 'uploading' ||
          task.status === 'downloading' ||
          task.status === 'checking' ||
          task.status === 'merging'
      )

      if (activeTasks.length === 0) {
        get().stopPolling()
        return
      }

      try {
        await get().syncTasks()
      } catch {
        // Silent
      }
    }, POLLING_INTERVAL)
  },

  stopPolling: () => {
    if (pollingTimerId !== null) {
      window.clearInterval(pollingTimerId)
      pollingTimerId = null
    }
  },

  setupBeforeUnloadWarning: () => {
    if (beforeUnloadWarningSetup) return
    beforeUnloadWarningSetup = true

    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      const { tasks } = get()
      const hasUploadingTasks = Array.from(tasks.values()).some(
        (task) =>
          task.status === 'idle' ||
          task.status === 'initialized' ||
          task.status === 'uploading' ||
          task.status === 'checking' ||
          task.status === 'merging' ||
          task.status === 'paused'
      )

      if (hasUploadingTasks) {
        event.preventDefault()
        event.returnValue = '有文件正在上传，离开页面将取消所有上传任务'
      }
    }

    window.addEventListener('beforeunload', handleBeforeUnload)
  },

  getDisplayData: (taskId) => {
    return progressCalculator.getDisplayData(taskId)
  },
}))
