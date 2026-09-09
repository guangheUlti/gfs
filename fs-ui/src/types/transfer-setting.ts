/**
 * 用户传输设置类型定义
 */

/**
 * 用户传输设置接口
 */
export interface TransferSetting {
  /** 主键ID */
  id?: number

  /** 用户ID */
  userId: string

  /** 下载速率限制 单位：MB/S，-1 表示不限制 */
  downloadSpeedLimit: number

  /** 并发上传数量 */
  concurrentUploadQuantity: number

  /** 并发下载数量 */
  concurrentDownloadQuantity: number

  /** 分片大小 单位：字节 */
  chunkSize: number

  /** 创建时间 */
  createdAt?: string

  /** 修改时间 */
  updatedAt?: string
}

/**
 * 更新传输设置请求参数
 */
export interface UpdateTransferSettingCmd {
  /** 下载速率限制 单位：MB/S（必填，-1表示不限制） */
  downloadSpeedLimit: number

  /** 并发上传数量（必填，最大3） */
  concurrentUploadQuantity: number

  /** 并发下载数量（必填，最大3） */
  concurrentDownloadQuantity: number

  /** 分片大小（必填，单位：字节） */
  chunkSize: number
}

/**
 * 传输设置表单数据（用于前端表单）
 */
export interface TransferSettingForm {
  /** 下载速率限制 单位：MB/S，-1 表示不限制 */
  downloadSpeedLimit: number

  /** 是否启用下载速率限制 */
  enableDownloadSpeedLimit: boolean

  /** 并发上传数量 */
  concurrentUploadQuantity: number

  /** 并发下载数量 */
  concurrentDownloadQuantity: number

  /** 分片大小 单位：字节 */
  chunkSize: number
}
