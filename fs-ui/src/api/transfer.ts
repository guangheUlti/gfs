import type {
  FileTransferTaskVO,
  InitUploadCmd,
  CheckUploadCmd,
  CheckUploadResultVO,
  InitDownloadCmd,
  InitDownloadResultVO,
  FolderDownloadTaskVO,
} from '@/types/transfer'
import { request } from './request'
import service from './request'

/**
 * 初始化上传
 */
export function initUpload(params: InitUploadCmd) {
  return request.post<string>('/apis/transfer/init', params)
}

/**
 * 校验文件
 */
export function checkUpload(params: CheckUploadCmd) {
  return request.post<CheckUploadResultVO>('/apis/transfer/check', params)
}

/**
 * 上传分片
 */
export function uploadChunk(
  file: Blob,
  taskId: string,
  chunkIndex: number,
  chunkMd5: string
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
    timeout: 60000,
  })
}

/**
 * 查询已上传的分片
 */
export function getUploadedChunks(taskId: string) {
  return request.get<number[]>(`/apis/transfer/chunks/${taskId}`)
}

/**
 * 合并分片
 */
export function mergeChunks(taskId: string) {
  return request.post<string>(`/apis/transfer/merge/${taskId}`)
}

/**
 * 取消上传任务
 */
export function cancelUpload(taskId: string) {
  return request.delete(`/apis/transfer/cancel/${taskId}`)
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
  return request.post<InitDownloadResultVO>('/apis/transfer/init-download', params)
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
    timeout: 120000,
  })
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
