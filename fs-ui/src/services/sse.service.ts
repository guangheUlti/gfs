import { getToken } from '@/utils/auth'
import type {
  SSEMessage,
  SSEMessageType,
  SSEProgressData,
  SSEStatusData,
  SSECompleteData,
  SSEErrorData,
} from '@/types/transfer'

export type SSEMessageHandler = (message: SSEMessage) => void
export type SSEConnectionHandler = (connected: boolean) => void

interface SSEServiceConfig {
  baseUrl: string
  endpoint: string
  syncOnReconnect: boolean
}

const DEFAULT_CONFIG: SSEServiceConfig = {
  baseUrl: import.meta.env.VITE_API_BASE_URL || '',
  endpoint: '/apis/transfer/sse',
  syncOnReconnect: true,
}

function parseProgressData(data: Record<string, unknown>): SSEProgressData {
  return {
    uploadedBytes: Number(data.uploadedBytes) || 0,
    totalBytes: Number(data.totalBytes) || 0,
    uploadedChunks: Number(data.uploadedChunks) || 0,
    totalChunks: Number(data.totalChunks) || 0,
  }
}

function parseStatusData(data: Record<string, unknown>): SSEStatusData {
  return {
    status: (data.status as string) || 'idle',
    message: data.message as string | undefined,
  } as SSEStatusData
}

function parseCompleteData(data: Record<string, unknown>): SSECompleteData {
  return {
    fileId: (data.fileId as string) || '',
    fileName: (data.fileName as string) || '',
    fileSize: Number(data.fileSize) || 0,
  }
}

function parseErrorData(data: Record<string, unknown>): SSEErrorData {
  return {
    code: (data.code as string) || 'UNKNOWN_ERROR',
    message: (data.message as string) || 'Unknown error occurred',
  }
}

class SSEService {
  private static instance: SSEService | null = null
  private eventSource: EventSource | null = null
  private currentUserId: string | null = null
  private messageHandlers: Set<SSEMessageHandler> = new Set()
  private connectionHandlers: Set<SSEConnectionHandler> = new Set()
  private config: SSEServiceConfig
  private connected = false
  private onReconnectSync: (() => Promise<void>) | null = null
  private reconnectAttempts = 0
  private reconnectTimerId: number | null = null
  private watchdogTimerId: number | null = null
  private lastEventAt = 0
  private readonly MAX_RECONNECT_ATTEMPTS = 5
  private readonly RECONNECT_BASE_DELAY = 2000
  // 服务端每 25s 发一次 ping，超过 45s 收不到任何事件就认为这条连接是僵尸连接
  private readonly LIVENESS_TIMEOUT = 45_000
  private readonly WATCHDOG_INTERVAL = 10_000

  private constructor(config: Partial<SSEServiceConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config }
  }

  public static getInstance(config?: Partial<SSEServiceConfig>): SSEService {
    if (!SSEService.instance) {
      SSEService.instance = new SSEService(config)
    }
    return SSEService.instance
  }

  public connect(userId: string): void {
    if (
      this.eventSource &&
      this.currentUserId === userId &&
      this.eventSource.readyState !== EventSource.CLOSED
    ) {
      return
    }

    // 已关闭的 EventSource 不会自己复活，必须换一个新的实例
    this.closeSource()

    this.currentUserId = userId

    const params = new URLSearchParams()
    params.set('userId', userId)
    // EventSource 不能自定义请求头，服务端又不读 cookie，只能像下载那样
    // 把带前缀的 token 放到查询参数（sa-token 已开启 is-read-body）
    const token = getToken()
    if (token) {
      params.set('Authorization', `Bearer ${token}`)
    }

    const url = `${this.config.baseUrl}${this.config.endpoint}?${params.toString()}`

    try {
      this.eventSource = new EventSource(url)
      this.setupEventListeners()
      this.startWatchdog()
    } catch (error) {
      console.error('SSE 连接失败:', error)
      this.setConnected(false)
    }
  }

  public disconnect(): void {
    this.stopWatchdog()
    this.clearReconnectTimer()
    this.closeSource()
    this.currentUserId = null
  }

  public isConnected(): boolean {
    return this.connected
  }

  public onMessage(handler: SSEMessageHandler): () => void {
    this.messageHandlers.add(handler)
    return () => {
      this.messageHandlers.delete(handler)
    }
  }

  public onConnectionChange(handler: SSEConnectionHandler): () => void {
    this.connectionHandlers.add(handler)
    return () => {
      this.connectionHandlers.delete(handler)
    }
  }

  public setReconnectSyncCallback(callback: () => Promise<void>): void {
    this.onReconnectSync = callback
  }

  private setConnected(connected: boolean): void {
    const wasConnected = this.connected
    this.connected = connected

    this.connectionHandlers.forEach((handler) => {
      try {
        handler(connected)
      } catch {
        // Silent
      }
    })

    if (!wasConnected && connected && this.config.syncOnReconnect) {
      this.triggerReconnectSync()
    }
  }

  private async triggerReconnectSync(): Promise<void> {
    if (this.onReconnectSync) {
      try {
        await this.onReconnectSync()
      } catch {
        // Silent
      }
    }
  }

  private setupEventListeners(): void {
    const source = this.eventSource
    if (!source) return

    source.onopen = () => {
      this.reconnectAttempts = 0
      this.markAlive()
      this.setConnected(true)
    }

    source.onerror = () => {
      // 任何错误都先按断开处理：CONNECTING 状态下浏览器会自己重试，
      // 但页面不能继续以为连接还在，否则高频轮询会退化成 30s 兜底轮询
      this.setConnected(false)

      if (source.readyState === EventSource.CLOSED) {
        this.scheduleReconnect()
      }
    }

    const markAlive = () => this.markAlive()
    source.addEventListener('connected', markAlive)
    source.addEventListener('ping', markAlive)

    source.addEventListener('progress', (event) => {
      this.markAlive()
      this.handleEvent('progress', event)
    })

    source.addEventListener('status', (event) => {
      this.markAlive()
      this.handleEvent('status', event)
    })

    source.addEventListener('complete', (event) => {
      this.markAlive()
      this.handleEvent('complete', event)
    })

    source.addEventListener('error', (event) => {
      if (event instanceof MessageEvent) {
        this.markAlive()
        this.handleEvent('error', event)
      }
    })

    source.onmessage = (event) => {
      this.markAlive()
      this.handleGenericMessage(event)
    }
  }

  private markAlive(): void {
    this.lastEventAt = Date.now()
  }

  private closeSource(): void {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
    }
    this.setConnected(false)
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimerId !== null) return

    this.reconnectAttempts += 1
    // 快速重试若干次后退到 30s 一次，不彻底放弃：网络恢复后要能自己接回来
    const delay =
      this.reconnectAttempts <= this.MAX_RECONNECT_ATTEMPTS
        ? this.RECONNECT_BASE_DELAY * this.reconnectAttempts
        : 30_000

    this.reconnectTimerId = window.setTimeout(() => {
      this.reconnectTimerId = null
      this.reconnectNow()
    }, delay)
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimerId !== null) {
      window.clearTimeout(this.reconnectTimerId)
      this.reconnectTimerId = null
    }
  }

  private reconnectNow(): void {
    const userId = this.currentUserId
    if (!userId) return

    this.closeSource()
    this.connect(userId)
  }

  private startWatchdog(): void {
    if (this.watchdogTimerId !== null) return

    this.watchdogTimerId = window.setInterval(() => {
      if (!this.connected || this.eventSource === null) return
      if (Date.now() - this.lastEventAt <= this.LIVENESS_TIMEOUT) return

      console.warn('SSE 连接静默超时，强制重连')
      this.reconnectNow()
    }, this.WATCHDOG_INTERVAL)
  }

  private stopWatchdog(): void {
    if (this.watchdogTimerId !== null) {
      window.clearInterval(this.watchdogTimerId)
      this.watchdogTimerId = null
    }
  }

  private handleEvent(type: SSEMessageType, event: Event): void {
    if (!(event instanceof MessageEvent)) return

    try {
      const rawData = JSON.parse(event.data)
      const message = this.parseMessage(type, rawData)

      if (message) {
        this.dispatchMessage(message)
      }
    } catch {
      // Silent
    }
  }

  private handleGenericMessage(event: MessageEvent): void {
    try {
      const rawData = JSON.parse(event.data)

      if (rawData.type && rawData.taskId) {
        const message = this.parseMessage(rawData.type, rawData)
        if (message) {
          this.dispatchMessage(message)
        }
      }
    } catch {
      // Silent
    }
  }

  private parseMessage(
    type: SSEMessageType,
    rawData: Record<string, unknown>
  ): SSEMessage | null {
    const taskId = rawData.taskId as string
    if (!taskId) return null

    const data = (rawData.data as Record<string, unknown>) || rawData

    switch (type) {
      case 'progress':
        return { type: 'progress', taskId, data: parseProgressData(data) }
      case 'status':
        return { type: 'status', taskId, data: parseStatusData(data) }
      case 'complete':
        return { type: 'complete', taskId, data: parseCompleteData(data) }
      case 'error':
        return { type: 'error', taskId, data: parseErrorData(data) }
      default:
        return null
    }
  }

  private dispatchMessage(message: SSEMessage): void {
    this.messageHandlers.forEach((handler) => {
      try {
        handler(message)
      } catch {
        // Silent
      }
    })
  }
}

export const sseService = SSEService.getInstance()
export { SSEService }
