import { downloadChunk, getDownloadedChunks, initDownload, pauseUpload } from '@/api/transfer'
import type { InitDownloadResultVO } from '@/types/transfer'
import { getToken } from '@/utils/auth'

export interface DownloadItemInput {
  fileId: string
  fileName: string
  fileSize: number
}

export interface DownloadStartMeta {
  taskId: string
  /** 可选：新任务由队列传入；恢复/重试场景依赖 OPFS，无需此字段 */
  fileId?: string
  fileName: string
  fileSize: number
  chunkSize: number
  totalChunks: number
  downloadedChunks?: number[]
  chunkConcurrency: number
}

interface DownloadTaskContext {
  taskId: string
  /** 后端文件 ID，OPFS 不可用时大文件走服务端流式直下需要 */
  fileId: string
  fileName: string
  fileSize: number
  chunkSize: number
  totalChunks: number
  downloadedChunks: Set<number>
  chunkConcurrency: number
  isPaused: boolean
  isCancelled: boolean
  saving: boolean
  activeRequests: Map<number, AbortController>
  retryCount: Map<number, number>
  opfsFileHandle: FileSystemFileHandle | null
  memoryChunks: Map<number, Blob> | null
  /** OPFS 同一文件不允许并发 writable，分片落盘串行化 */
  writeChain: Promise<void>
}

/** createWritable 尚未进标准 TS DOM lib，用最小结构约定 */
interface WritableFileStreamLike {
  write(params: { type: 'write'; position: number; data: Blob }): Promise<void>
  close(): Promise<void>
}

export interface DownloadProgressData {
  uploadedBytes: number
  totalBytes: number
  uploadedChunks: number
  totalChunks: number
}

export interface DownloadExecutorCallbacks {
  onTransition: (taskId: string, status: string) => void
  onProgress: (taskId: string, data: DownloadProgressData) => void
  onError: (taskId: string, errorMessage: string) => void
  /** 排队任务真正调用 initDownload 后，用后端 taskId 替换占位 tempId */
  onTaskIdReplaced: (
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
}

function isNetworkError(error: unknown): boolean {
  if (error instanceof Error) {
    const message = error.message.toLowerCase()
    return (
      message.includes('network') ||
      message.includes('timeout') ||
      message.includes('abort') ||
      message.includes('connection') ||
      message.includes('fetch')
    )
  }
  return false
}

function isConcurrencyLimitError(error: unknown): boolean {
  const message =
    error instanceof Error
      ? error.message
      : ((error as { response?: { data?: { msg?: string } } })?.response?.data
          ?.msg ?? '')
  return message.includes('并发下载')
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

interface QueueItem extends DownloadItemInput {
  tempId: string
}

class DownloadExecutor {
  private static instance: DownloadExecutor | null = null
  public readonly MAX_RETRY_COUNT = 3
  private readonly RETRY_BASE_DELAY = 1000
  /** 不限速时任务内并行拉取的分片数 */
  private readonly UNLIMITED_CHUNK_CONCURRENCY = 3
  /** OPFS 不可用时允许内存降级的最大文件大小（200MB）。
   * 内存模式下分片 Blob 全部驻留 JS 堆，组装保存阶段还要翻倍，
   * 上限过高极易把标签页内存打爆（表现为卡死、保存框不弹出），
   * 超出部分改走后端流式直下由浏览器原生落盘 */
  private readonly MEMORY_FALLBACK_MAX_SIZE = 200 * 1024 * 1024

  private taskContexts = new Map<string, DownloadTaskContext>()
  /** 已通过 initDownload 占住后端并发名额的任务（含暂停中，终态才释放） */
  private active = new Set<string>()
  /** 内存降级模式全局单槽：http 部署下分片 Blob 全驻 JS 堆，
   * 同时只允许一个内存任务，避免双大文件并发把标签页内存打爆 */
  private memorySlotHolder: string | null = null
  private memorySlotWaiters: Array<() => void> = []
  private queue: QueueItem[] = []
  /** initDownload 因后端并发上限被拒后暂停出队，待名额释放再继续 */
  private queueBlocked = false
  private maxConcurrency = 3
  private speedLimited = false
  private tempIdCounter = 0
  private callbacks: DownloadExecutorCallbacks | null = null

  public static getInstance(): DownloadExecutor {
    if (!DownloadExecutor.instance) {
      DownloadExecutor.instance = new DownloadExecutor()
    }
    return DownloadExecutor.instance
  }

  public setCallbacks(callbacks: DownloadExecutorCallbacks): void {
    this.callbacks = callbacks
  }

  /** maxConcurrentTasks：同时下载数量；speedLimited：是否启用下载限速 */
  public configure(maxConcurrentTasks: number, speedLimited: boolean): void {
    this.maxConcurrency = Math.max(1, maxConcurrentTasks)
    this.speedLimited = speedLimited
    this.pump()
  }

  /** 入队一个下载项，返回占位 tempId（真实 taskId 由 onTaskIdReplaced 回调替换） */
  public enqueue(item: DownloadItemInput): string {
    const tempId = `dl-temp-${Date.now()}-${(this.tempIdCounter += 1)}`
    this.queue.push({ ...item, tempId })
    this.pump()
    return tempId
  }

  public isQueued(taskId: string): boolean {
    return this.queue.some((item) => item.tempId === taskId)
  }

  public removeFromQueue(taskId: string): boolean {
    const before = this.queue.length
    this.queue = this.queue.filter((item) => item.tempId !== taskId)
    return this.queue.length < before
  }

  /** 刷新页面后，本地（OPFS）是否留有该任务的分片临时文件 */
  public async hasLocalProgress(taskId: string): Promise<boolean> {
    try {
      if (!navigator.storage?.getDirectory) return false
      const root = await navigator.storage.getDirectory()
      await root.getFileHandle(this.tempFileName(taskId), { create: false })
      return true
    } catch {
      return false
    }
  }

  /**
   * 该文件是否会走流式直下（http 无 OPFS 且超出内存模式上限）：
   * 浏览器原生落盘，页面内无进度可看，调用方可据此决定是否跳转传输页
   */
  public willUseNativeDownload(fileSize: number): boolean {
    if (navigator.storage?.getDirectory) return false
    return fileSize > this.MEMORY_FALLBACK_MAX_SIZE
  }

  /**
   * 直接触发浏览器原生下载（不建后端任务、不进执行器队列）：
   * 供 store 在添加任务阶段对流式直下文件分流使用——此类下载
   * 进度由浏览器接管，页面内无法监控，不应出现在传输列表里
   */
  public openNativeDownload(fileId: string): void {
    this.openNativeDownloadUrl(fileId)
  }

  /** 恢复上次会话遗留的下载任务（本地 OPFS 有临时文件时由 store 调用） */
  public async adoptResumed(meta: DownloadStartMeta): Promise<void> {
    const context = this.createContext(meta)
    await this.sanitizeLocalProgress(context)
    this.taskContexts.set(meta.taskId, context)
    this.active.add(meta.taskId)
    void this.runFetch(context)
  }

  public pause(taskId: string): boolean {
    const context = this.taskContexts.get(taskId)
    if (!context) return false
    context.isPaused = true
    context.activeRequests.forEach((controller) => controller.abort())
    context.activeRequests.clear()
    return true
  }

  public async resume(taskId: string): Promise<void> {
    const context = this.taskContexts.get(taskId)
    if (!context) throw new Error(`Download task context not found: ${taskId}`)

    context.isPaused = false
    try {
      const backendChunks = (await getDownloadedChunks(taskId)) || []
      context.downloadedChunks = new Set(backendChunks)
      await this.sanitizeLocalProgress(context)
      this.notifyProgress(taskId, context)
      await this.runFetch(context)
    } catch (error) {
      this.handleFetchError(taskId, context, error)
    }
  }

  /** 失败任务重试：复用原上下文重新拉取缺失分片 */
  public async retry(taskId: string): Promise<void> {
    const context = this.taskContexts.get(taskId)
    if (!context) throw new Error(`Download task context not found: ${taskId}`)

    context.isPaused = false
    context.isCancelled = false
    context.retryCount.clear()
    try {
      const backendChunks = (await getDownloadedChunks(taskId)) || []
      context.downloadedChunks = new Set(backendChunks)
      await this.sanitizeLocalProgress(context)
      await this.runFetch(context)
    } catch (error) {
      this.handleFetchError(taskId, context, error)
    }
  }

  public cancel(taskId: string): void {
    if (this.removeFromQueue(taskId)) return

    const context = this.taskContexts.get(taskId)
    if (!context) {
      this.releaseMemorySlot(taskId)
      return
    }

    context.isCancelled = true
    context.activeRequests.forEach((controller) => controller.abort())
    context.activeRequests.clear()

    void this.removeOpfsTemp(context)
      .catch(() => undefined)
      .finally(() => {
        this.taskContexts.delete(taskId)
        this.releaseMemorySlot(taskId)
        this.releaseSlotAndPump(taskId)
      })
  }

  public getTaskContext(taskId: string): DownloadTaskContext | undefined {
    return this.taskContexts.get(taskId)
  }

  public clearAll(): void {
    this.taskContexts.forEach((context) => {
      context.isCancelled = true
      context.activeRequests.forEach((controller) => controller.abort())
    })
    this.taskContexts.clear()
    this.active.clear()
    this.queue = []
    this.memorySlotHolder = null
    this.memorySlotWaiters.forEach((wake) => wake())
    this.memorySlotWaiters = []
  }

  // ==================== 内部流程 ====================

  private createContext(meta: DownloadStartMeta): DownloadTaskContext {
    return {
      taskId: meta.taskId,
      fileId: meta.fileId ?? '',
      fileName: meta.fileName,
      fileSize: meta.fileSize,
      chunkSize: meta.chunkSize,
      totalChunks: meta.totalChunks,
      downloadedChunks: new Set(meta.downloadedChunks ?? []),
      chunkConcurrency: meta.chunkConcurrency,
      isPaused: false,
      isCancelled: false,
      saving: false,
      activeRequests: new Map(),
      retryCount: new Map(),
      opfsFileHandle: null,
      memoryChunks: null,
      writeChain: Promise.resolve(),
    }
  }

  private async pump(): Promise<void> {
    if (this.queueBlocked) return

    while (this.queue.length > 0 && this.active.size < this.maxConcurrency) {
      const item = this.queue.shift()!
      let vo: InitDownloadResultVO
      try {
        vo = await initDownload({
          fileId: item.fileId,
          chunkSize: undefined,
        })
      } catch (error) {
        if (isConcurrencyLimitError(error)) {
          this.queue.unshift(item)
          this.queueBlocked = true
          return
        }
        this.notifyError(
          item.tempId,
          error instanceof Error ? error.message : '初始化下载任务失败'
        )
        continue
      }

      const chunkConcurrency = this.speedLimited
        ? 1
        : this.UNLIMITED_CHUNK_CONCURRENCY
      this.callbacks?.onTaskIdReplaced(item.tempId, vo.taskId, {
        fileName: vo.fileName,
        fileSize: vo.fileSize,
        chunkSize: vo.chunkSize,
        totalChunks: vo.totalChunks,
        downloadedChunks: vo.downloadedChunks ?? [],
      })

      const context = this.createContext({
        taskId: vo.taskId,
        fileId: item.fileId,
        fileName: vo.fileName,
        fileSize: vo.fileSize,
        chunkSize: vo.chunkSize,
        totalChunks: vo.totalChunks,
        downloadedChunks: vo.downloadedChunks ?? [],
        chunkConcurrency,
      })
      this.taskContexts.set(vo.taskId, context)
      this.active.add(vo.taskId)
      void this.runFetch(context)
    }
  }

  private releaseSlotAndPump(taskId: string): void {
    this.active.delete(taskId)
    if (this.active.size < this.maxConcurrency) {
      this.queueBlocked = false
    }
    this.pump()
  }

  private async runFetch(context: DownloadTaskContext): Promise<void> {
    const { taskId, totalChunks, downloadedChunks } = context

    if (!(await this.ensureStorage(context))) {
      // OPFS 仅在安全上下文（HTTPS / localhost）可用：http 部署时
      // navigator.storage.getDirectory 不存在。
      // 小文件降级内存模式保可用；大文件改走后端流式直下接口，
      // 由浏览器原生下载落盘，无前端内存上限。
      if (context.fileSize <= this.MEMORY_FALLBACK_MAX_SIZE) {
        // 内存模式全局互斥：等已有内存任务释放槽位后再继续
        if (!(await this.acquireMemorySlot(context))) return
        context.memoryChunks = new Map()
      } else if (context.fileId) {
        this.triggerNativeDownload(context)
        return
      } else {
        this.notifyError(
          taskId,
          '当前站点未启用 HTTPS，浏览器不支持本地下载缓存，且文件超出内存下载上限（200MB），请通过 HTTPS 访问后重试'
        )
        return
      }
    }

    try {
      await this.fetchChunks(context)
    } catch (error) {
      this.handleFetchError(taskId, context, error)
      return
    }

    if (context.isCancelled || context.isPaused) return

    if (downloadedChunks.size === totalChunks) {
      await this.finishDownload(context)
    }
  }

  private async fetchChunks(context: DownloadTaskContext): Promise<void> {
    const { totalChunks, downloadedChunks, chunkConcurrency } = context

    const pending: number[] = []
    for (let i = 0; i < totalChunks; i += 1) {
      if (!downloadedChunks.has(i)) pending.push(i)
    }
    if (pending.length === 0) return

    let cursor = 0

    const worker = async (): Promise<void> => {
      while (cursor < pending.length) {
        if (context.isPaused || context.isCancelled) return

        const chunkIndex = pending[cursor]
        cursor += 1

        const ok = await this.fetchChunkWithRetry(context, chunkIndex)
        if (ok) {
          context.downloadedChunks.add(chunkIndex)
          this.notifyProgress(context.taskId, context)
        } else if (!context.isCancelled && !context.isPaused) {
          throw new Error(`分片 ${chunkIndex} 下载失败`)
        }
      }
    }

    const workerCount = Math.min(chunkConcurrency, pending.length)
    await Promise.all(Array.from({ length: workerCount }, () => worker()))
  }

  private async fetchChunkWithRetry(
    context: DownloadTaskContext,
    chunkIndex: number
  ): Promise<boolean> {
    const { taskId } = context
    let retryCount = context.retryCount.get(chunkIndex) || 0

    while (retryCount <= this.MAX_RETRY_COUNT) {
      if (context.isPaused || context.isCancelled) return false

      const abortController = new AbortController()
      context.activeRequests.set(chunkIndex, abortController)

      try {
        const response = await downloadChunk(
          taskId,
          chunkIndex,
          abortController.signal
        )
        const blob = response.data
        context.activeRequests.delete(chunkIndex)
        context.retryCount.delete(chunkIndex)
        await this.writeChunk(context, chunkIndex, blob)
        return true
      } catch {
        context.activeRequests.delete(chunkIndex)

        if (context.isCancelled || context.isPaused) return false

        retryCount += 1
        context.retryCount.set(chunkIndex, retryCount)

        if (retryCount <= this.MAX_RETRY_COUNT) {
          await sleep(this.RETRY_BASE_DELAY * 2 ** (retryCount - 1))
        } else {
          return false
        }
      }
    }
    return false
  }

  private handleFetchError(
    taskId: string,
    context: DownloadTaskContext,
    error: unknown
  ): void {
    if (context.isCancelled) {
      this.releaseMemorySlot(taskId)
      return
    }

    if (isNetworkError(error) || context.isPaused) {
      // 网络中断自动转暂停；同步后端状态，保证后续 resume 接口可用
      // 暂停任务保留内存槽位（分片 Blob 仍驻留堆中），resume 时直接复用
      context.isPaused = true
      void pauseUpload(taskId).catch(() => undefined)
      this.notifyTransition(taskId, 'paused')
      return
    }

    this.releaseMemorySlot(taskId)
    this.notifyError(
      taskId,
      error instanceof Error ? error.message : '下载失败'
    )
  }

  /** 内存模式任务进入前获取全局唯一槽位；取消/暂停时返回 false */
  private async acquireMemorySlot(
    context: DownloadTaskContext
  ): Promise<boolean> {
    for (;;) {
      if (context.isCancelled || context.isPaused) return false
      if (
        this.memorySlotHolder === null ||
        this.memorySlotHolder === context.taskId
      ) {
        this.memorySlotHolder = context.taskId
        return true
      }
      await new Promise<void>((resolve) => {
        this.memorySlotWaiters.push(resolve)
      })
    }
  }

  private releaseMemorySlot(taskId: string): void {
    if (this.memorySlotHolder !== taskId) return
    this.memorySlotHolder = null
    this.memorySlotWaiters.shift()?.()
  }

  private async finishDownload(context: DownloadTaskContext): Promise<void> {
    const { taskId } = context
    if (context.saving) return
    context.saving = true

    this.notifyTransition(taskId, 'merging')

    try {
      // 等待所有在途分片写完，再组装保存
      await context.writeChain

      let blob: Blob
      if (context.opfsFileHandle) {
        // OPFS 后端的 File 无法被浏览器主进程读取，直接 <a download> 会报
        // 「无法下载 - 网络问题」，必须先拷贝为内存 blob 再触发保存
        const file = await context.opfsFileHandle.getFile()
        blob = new Blob([await file.arrayBuffer()])
      } else {
        const ordered: Blob[] = []
        for (let i = 0; i < context.totalChunks; i += 1) {
          ordered.push(context.memoryChunks?.get(i) ?? new Blob())
        }
        blob = new Blob(ordered)
      }

      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = context.fileName
      link.style.display = 'none'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      setTimeout(() => URL.revokeObjectURL(url), 60_000)

      await this.removeOpfsTemp(context)
    } catch (error) {
      // 组装/保存失败必须暴露出来，否则任务永远停在 merging 假死
      this.releaseMemorySlot(taskId)
      this.notifyError(
        taskId,
        error instanceof Error
          ? `文件组装或保存失败：${error.message}`
          : '文件组装或保存失败'
      )
      return
    } finally {
      this.taskContexts.delete(taskId)
      this.releaseSlotAndPump(taskId)
    }

    this.releaseMemorySlot(taskId)
    this.notifyTransition(taskId, 'completed')
  }

  /**
   * 大文件 + OPFS 不可用（http 部署）时的兜底：走后端流式下载接口
   * GET /apis/transfer/download/{fileId}（attachment），浏览器原生下载落盘，
   * 无前端内存上限。进度由浏览器接管，任务直接置为完成；token 经查询参数
   * 传递（sa-token is-read-body 专为无法自定义请求头的下载/SSE 场景预留）。
   */
  private triggerNativeDownload(context: DownloadTaskContext): void {
    const { taskId, fileId } = context
    try {
      this.openNativeDownloadUrl(fileId)
      this.notifyTransition(taskId, 'completed')
    } catch {
      this.notifyError(
        taskId,
        '无法创建本地下载缓存，且浏览器直连下载触发失败，请通过 HTTPS 访问后重试'
      )
    } finally {
      this.taskContexts.delete(taskId)
      this.releaseSlotAndPump(taskId)
    }
  }

  /** 构造直下下载 URL 并用隐藏 a 标签触发浏览器原生下载 */
  private openNativeDownloadUrl(fileId: string): void {
    const token = getToken()
    const base =
      (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? ''
    const params = new URLSearchParams()
    if (token) params.set('Authorization', `Bearer ${token}`)
    const query = params.size > 0 ? `?${params.toString()}` : ''
    const url = `${base}/apis/transfer/download/${fileId}${query}`

    const link = document.createElement('a')
    link.href = url
    link.rel = 'noopener'
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  }

  // ==================== 本地存储（OPFS 优先 / 内存降级） ====================

  private tempFileName(taskId: string): string {
    return `download-${taskId}.part`
  }

  private async ensureStorage(context: DownloadTaskContext): Promise<boolean> {
    if (context.opfsFileHandle || context.memoryChunks) return true
    try {
      if (!navigator.storage?.getDirectory) return false
      const root = await navigator.storage.getDirectory()
      context.opfsFileHandle = await root.getFileHandle(
        this.tempFileName(context.taskId),
        { create: true }
      )
      return true
    } catch {
      return false
    }
  }

  /**
   * OPFS createWritable 是 close 时才原子落盘，故每分片独立 open→write→close。
   * 同一文件不允许并发 writable，用 writeChain 串行化。
   */
  private writeChunk(
    context: DownloadTaskContext,
    chunkIndex: number,
    blob: Blob
  ): void {
    if (context.opfsFileHandle) {
      context.writeChain = context.writeChain.then(async () => {
        if (context.isCancelled) return
        const handle = context.opfsFileHandle as (FileSystemFileHandle & {
          createWritable?: (opts?: {
            keepExistingData?: boolean
          }) => Promise<WritableFileStreamLike>
        }) | null
        if (!handle?.createWritable) {
          context.memoryChunks = context.memoryChunks ?? new Map()
          context.memoryChunks.set(chunkIndex, blob)
          return
        }
        const writable = await handle.createWritable({
          keepExistingData: true,
        })
        try {
          await writable.write({
            type: 'write',
            position: chunkIndex * context.chunkSize,
            data: blob,
          })
        } finally {
          await writable.close()
        }
      })
    } else {
      context.memoryChunks?.set(chunkIndex, blob)
    }
  }

  /**
   * 后端 Redis 的分片记录早于本地落盘，崩溃窗口内可能出现
   * 「Redis 记为已下载但本地缺字节」的空洞。用文件尺寸做下限校验，
   * 不满足则丢弃本地进度整体重下，避免拼出损坏文件。
   */
  private async sanitizeLocalProgress(
    context: DownloadTaskContext
  ): Promise<void> {
    if (!context.opfsFileHandle && !context.memoryChunks) {
      await this.ensureStorage(context)
    }
    if (!context.opfsFileHandle) {
      // 内存模式（OPFS 不可用）没有本地临时文件可校验/续传，
      // 丢弃 Redis 记录的整体重下，避免拼出含空洞的文件
      context.downloadedChunks = new Set()
      return
    }

    try {
      const file = await context.opfsFileHandle.getFile()
      let maxIndex = -1
      context.downloadedChunks.forEach((index) => {
        if (index > maxIndex) maxIndex = index
      })
      if (maxIndex < 0) return

      const expectedMin =
        Math.min((maxIndex + 1) * context.chunkSize, context.fileSize)
      if (file.size < expectedMin) {
        context.downloadedChunks = new Set()
        await this.removeOpfsTemp(context)
        await this.ensureStorage(context)
      }
    } catch {
      context.downloadedChunks = new Set()
    }
  }

  private async removeOpfsTemp(context: DownloadTaskContext): Promise<void> {
    if (!context.opfsFileHandle) return
    const name = this.tempFileName(context.taskId)
    context.opfsFileHandle = null
    try {
      const root = await navigator.storage.getDirectory()
      await root.removeEntry(name)
    } catch {
      // 临时文件可能已不存在
    }
  }

  // ==================== 通知 ====================

  private notifyTransition(taskId: string, status: string): void {
    this.callbacks?.onTransition(taskId, status)
  }

  private notifyProgress(taskId: string, context: DownloadTaskContext): void {
    if (!this.callbacks?.onProgress) return

    const { chunkSize, fileSize, downloadedChunks, totalChunks } = context

    let uploadedBytes = 0
    downloadedChunks.forEach((chunkIndex) => {
      const start = chunkIndex * chunkSize
      uploadedBytes += Math.min(start + chunkSize, fileSize) - start
    })

    this.callbacks.onProgress(taskId, {
      uploadedBytes,
      totalBytes: fileSize,
      uploadedChunks: downloadedChunks.size,
      totalChunks,
    })
  }

  private notifyError(taskId: string, errorMessage: string): void {
    this.callbacks?.onError(taskId, errorMessage)
  }
}

export const downloadExecutor = DownloadExecutor.getInstance()
