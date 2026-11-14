/**
 * 任务状态联合类型
 */
export type TaskStatus =
  | 'idle'
  | 'initialized'
  | 'checking'
  | 'uploading'
  | 'downloading'
  | 'paused'
  | 'merging'
  | 'completed'
  | 'failed'
  | 'cancelled'

/**
 * 任务类型
 */
export type TransferTaskType = 'upload' | 'download'

/**
 * 传输任务接口
 */
export interface TransferTask {
  taskId: string
  taskType: TransferTaskType
  fileName: string
  fileSize: number
  status: TaskStatus
  progress: number
  uploadedBytes: number
  speed: number
  remainingTime: number
  errorMessage?: string
  createdAt: number
  updatedAt: number
  parentId?: string
  mimeType?: string
  fileMd5?: string
  totalChunks?: number
  uploadedChunks?: number
  chunkSize?: number
}

/**
 * 进度更新数据
 */
export interface ProgressUpdate {
  uploadedBytes: number
  totalBytes: number
  uploadedChunks?: number
  totalChunks?: number
}

/**
 * SSE 消息类型
 */
export type SSEMessageType = 'progress' | 'status' | 'complete' | 'error'

/**
 * SSE 进度数据
 */
export interface SSEProgressData {
  uploadedBytes: number
  totalBytes: number
  uploadedChunks: number
  totalChunks: number
}

/**
 * SSE 状态变更数据
 */
export interface SSEStatusData {
  status: TaskStatus
  message?: string
}

/**
 * SSE 完成数据
 */
export interface SSECompleteData {
  fileId: string
  fileName: string
  fileSize: number
}

/**
 * SSE 错误数据
 */
export interface SSEErrorData {
  code: string
  message: string
}

/**
 * SSE 消息联合类型
 */
export type SSEMessageData =
  | SSEProgressData
  | SSEStatusData
  | SSECompleteData
  | SSEErrorData

/**
 * SSE 消息接口
 */
export interface SSEMessage {
  type: SSEMessageType
  taskId: string
  data: SSEMessageData
}

/**
 * 初始化上传请求参数
 */
export interface InitUploadCmd {
  fileName: string
  fileSize: number
  parentId?: string
  totalChunks: number
  chunkSize: number
  mimeType: string
  /** 同名冲突策略：replace 覆盖原文件；keepBoth/空 自动重命名保留两者 */
  conflictPolicy?: ConflictPolicy
}

/**
 * 上传同名冲突策略
 * - keepBoth：保留两者，服务端自动生成「name(1).ext」
 * - replace：覆盖目标目录中的同名文件（文件 ID 不变）
 */
export type ConflictPolicy = 'replace' | 'keepBoth'

/**
 * 目标目录中已存在的同名项
 */
export interface FileConflictVO {
  fileName: string
  fileId: string
  size: number | null
  uploadTime?: string
  /** true 表示同名项是已存在的目录 */
  isDir?: boolean
}

/**
 * 上传前冲突询问的结果
 * - policy 为 null 表示用户取消上传
 * - existingDirIds 仅在选择「替换」时填充：顶层同名目录名 → 已存在目录 ID，用于合并上传
 */
export interface UploadConflictDecision {
  policy: ConflictPolicy
  existingDirIds: Record<string, string>
}

/**
 * 初始化上传响应结果
 */
export interface InitUploadResultVO {
  taskId: string
  /** 当前存储是否支持按内容去重（秒传）；false 时不必算整文件 MD5 */
  dedupeSupported: boolean
}

/**
 * 一次性上传（单分片）响应结果，字段与校验结果一致
 */
export interface UploadOnceResultVO {
  isQuickUpload: boolean
  fileId?: string
  taskId: string
  message?: string
}

/**
 * 校验上传请求参数
 */
export interface CheckUploadCmd {
  taskId: string
  fileMd5: string
  fileName: string
}

/**
 * 校验上传响应结果
 */
export interface CheckUploadResultVO {
  isQuickUpload: boolean
  fileId?: string
  taskId: string
  message?: string
}

/**
 * 初始化下载请求参数
 */
export interface InitDownloadCmd {
  fileId: string
  chunkSize?: number
}

/**
 * 初始化下载响应结果
 */
export interface InitDownloadResultVO {
  taskId: string
  fileName: string
  fileSize: number
  totalChunks: number
  chunkSize: number
  downloadedChunks: number[]
}

/**
 * 文件夹批量打包下载任务状态
 */
export type FolderDownloadTaskStatus =
  | 'queued'
  | 'scaning'
  | 'scanning'
  | 'packing'
  | 'completed'
  | 'failed'
  | 'canceled'

/**
 * 文件夹批量打包下载任务VO
 */
export interface FolderDownloadTaskVO {
  taskId: string
  status: FolderDownloadTaskStatus
  progress: number
  totalFiles: number
  processedFiles: number
  message?: string
  errorMessage?: string
}

/**
 * 文件传输任务VO
 */
export interface FileTransferTaskVO {
  taskId: string
  taskType: 'upload' | 'download'
  userId: string
  parentId?: string
  objectKey: string
  fileName: string
  fileSize: number
  suffix?: string
  totalChunks: number
  uploadedChunks: number
  chunkSize: number
  storagePlatformSettingId: string
  status: TaskStatus
  errorMsg?: string
  startTime?: string
  completeTime?: string
  progress?: number
  speed?: number
  remainTime?: number
  uploadedSize?: number
}
