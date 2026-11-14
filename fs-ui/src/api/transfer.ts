import type {
  FileTransferTaskVO,
  InitUploadCmd,
  InitUploadResultVO,
  UploadOnceResultVO,
  CheckUploadCmd,
  CheckUploadResultVO,
  InitDownloadCmd,
  InitDownloadResultVO,
  FolderDownloadTaskVO,
  ConflictPolicy,
  FileConflictVO,
} from '@/types/transfer'
import { request, service } from './request'

/**
 * 初始化上传
 * 并发上传带宽饱和时，init 需等服务端建任务记录，留足超时避免误报
 */
export function initUpload(params: InitUploadCmd) {
  return request.post<InitUploadResultVO>('/apis/transfer/init', params, {
    timeout: 30000,
    // 传输分片/控制请求的错误由执行器统一上报（含取消竞态过滤），
    // 拦截器层静默，避免取消瞬间每个在途分片都弹一个「任务不存在」
    showErrorMessage: false,
  })
}

/**
 * 一次性上传单个分片的文件：init + 校验 + 写入 + 合并合成一次请求。
 * MD5 由服务端计算，小文件不必在上传前做全量哈希。
 */
export function uploadOnce(
  file: File,
  params: {
    fileName: string
    parentId?: string
    chunkSize: number
    mimeType: string
    conflictPolicy?: ConflictPolicy
  }
) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileName', params.fileName)
  formData.append('fileSize', String(file.size))
  if (params.parentId) formData.append('parentId', params.parentId)
  formData.append('chunkSize', String(params.chunkSize))
  formData.append('mimeType', params.mimeType)
  if (params.conflictPolicy) {
    formData.append('conflictPolicy', params.conflictPolicy)
  }
  return request.post<UploadOnceResultVO>('/apis/transfer/upload-once', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    showErrorMessage: false,
  })
}

/**
 * 批量检测目标目录中的同名文件/目录，用于上传前询问「替换 / 保留两者」
 */
export function checkUploadConflicts(
  parentId: string | undefined,
  fileNames: string[],
  dirNames: string[] = []
) {
  return request.post<FileConflictVO[]>(
    '/apis/transfer/conflicts',
    { parentId, fileNames, dirNames },
    { timeout: 30000, showErrorMessage: false }
  )
}

/**
 * 校验文件（秒传 MD5 比对服务端需查缓存/库）
 */
export function checkUpload(params: CheckUploadCmd) {
  return request.post<CheckUploadResultVO>('/apis/transfer/check', params, {
    timeout: 30000,
    showErrorMessage: false,
  })
}

/**
 * 上传分片
 */
export function uploadChunk(
  file: Blob,
  taskId: string,
  chunkIndex: number,
  chunkMd5: string,
  signal?: AbortSignal
) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('taskId', taskId)
  formData.append('chunkIndex', chunkIndex.toString())
  formData.append('chunkMd5', chunkMd5)

  return request.post('/apis/transfer/chunk', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
    // 分片档位最大 1GB 且服务端同步写入：慢链路下单请求可能远超 60s
    timeout: 30 * 60 * 1000,
    showErrorMessage: false,
    signal,
  })
}

/**
 * 查询已上传的分片
 */
export function getUploadedChunks(taskId: string) {
  return request.get<number[]>(`/apis/transfer/chunks/${taskId}`, {
    timeout: 30000,
    showErrorMessage: false,
  })
}

/**
 * 合并分片（服务端按序落盘合并大文件，可能远超普通接口耗时）
 */
export function mergeChunks(taskId: string) {
  return request.post<string>(`/apis/transfer/merge/${taskId}`, undefined, {
    timeout: 300000,
    showErrorMessage: false,
  })
}

/**
 * 取消上传任务
 */
export function cancelUpload(taskId: string) {
  return request.delete(`/apis/transfer/cancel/${taskId}`, {
    // 「任务不存在」由 store 判定为取消成功（竞态容忍），无需拦截器弹窗
    showErrorMessage: false,
  })
}

/**
 * 获取传输文件列表
 */
export function getTransferFiles() {
  return request.get<FileTransferTaskVO[]>('/apis/transfer/files')
}

/**
 * 暂停上传任务
 */
export function pauseUpload(taskId: string) {
  return request.post(`/apis/transfer/pause/${taskId}`)
}

/**
 * 恢复上传任务
 */
export function resumeUpload(taskId: string) {
  return request.post(`/apis/transfer/resume/${taskId}`)
}

/**
 * 清空已完成任务
 */
export function clearCompletedTasks() {
  return request.delete('/apis/transfer/clears')
}

/**
 * 初始化下载任务
 */
export function initDownload(params: InitDownloadCmd) {
  return request.post<InitDownloadResultVO>('/apis/transfer/init-download', params, {
    showErrorMessage: false,
  })
}

/**
 * 下载分片（blob 响应，需取 response.data）
 */
export function downloadChunk(
  taskId: string,
  chunkIndex: number,
  signal?: AbortSignal
) {
  return service.get<Blob>('/apis/transfer/download/chunk', {
    params: { taskId, chunkIndex },
    responseType: 'blob',
    signal,
    // 下载分片服务端回退到最大 64MB，慢链路 + 限速时 120s 不够
    timeout: 10 * 60 * 1000,
    // axios 原生调用，拦截器 toast 不生效，但保持配置一致
    showErrorMessage: false,
  } as any)
}

/**
 * 查询已下载分片索引
 */
export function getDownloadedChunks(taskId: string) {
  return request.get<number[]>(`/apis/transfer/download/chunks/${taskId}`)
}

/**
 * 创建批量 zip 打包下载任务
 */
export function createBatchDownloadTask(ids: string[]) {
  return request.post<FolderDownloadTaskVO>(
    '/apis/transfer/batch-download/tasks',
    ids
  )
}

/**
 * 查询批量 zip 打包下载任务进度
 */
export function getFolderDownloadTask(taskId: string) {
  return request.get<FolderDownloadTaskVO>(
    `/apis/transfer/folder-download/tasks/${taskId}`
  )
}

/**
 * 从 Content-Disposition 响应头中解析文件名
 */
function getFileNameFromContentDisposition(
  value: string | undefined
): string | undefined {
  if (!value) return undefined
  // 优先匹配 RFC5987 的 filename*=UTF-8''<encoded>，再回退到普通 filename=
  const star = value.match(/filename\*=UTF-8''([^;]+)/i)
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      // 解码失败时回退到普通 filename
    }
  }
  const plain = value.match(/filename="?([^";]+)"?/i)
  return plain?.[1]?.trim() || undefined
}

/**
 * 下载已打包好的 zip（触发浏览器下载）
 */
export async function downloadFolderDownloadZip(taskId: string) {
  const res = await service.get<Blob>(
    `/apis/transfer/folder-download/tasks/${taskId}/file`,
    { responseType: 'blob' }
  )
  const blob = res.data
  const fileName =
    getFileNameFromContentDisposition(res.headers?.['content-disposition']) ||
    'batch-download.zip'

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
