import { request } from './request'

export interface ServiceSettingVO {
  serviceType: 'webdav' | 'sftp' | 'ftp' | 'oss'
  enabled: boolean
  /** socket 型服务（sftp/ftp）有值；webdav/oss 复用主 HTTP 端口为 null */
  port: number | null
  bindAddress: string
  /** running / stopped / error */
  status: 'running' | 'stopped' | 'error'
  /** status=error 时的错误信息 */
  error: string | null
  updatedAt: string | null
}

export interface UpdateServiceSettingParams {
  enabled: boolean
  /** 仅 SFTP，1024-65535 */
  port?: number
  /** 仅 SFTP */
  bindAddress?: string
}

export type ServiceAction = 'start' | 'stop' | 'restart'

/**
 * 全部对外文件服务配置 + 实时运行状态
 */
export function listServiceSettings() {
  return request.get<ServiceSettingVO[]>('/apis/service/list')
}

/**
 * 保存配置并热生效（启动失败不抛错，经 list 的 status=error 体现）
 */
export function updateServiceConfig(
  type: ServiceSettingVO['serviceType'],
  data: UpdateServiceSettingParams
) {
  return request.put<void>(`/apis/service/${type}/config`, data)
}

/**
 * start / stop / restart
 */
export function serviceAction(
  type: ServiceSettingVO['serviceType'],
  action: ServiceAction
) {
  return request.post<void>(`/apis/service/${type}/${action}`)
}

// ---------- OSS 访问密钥（SigV4） ----------

export interface OssAccessKeyVO {
  id: string
  accessKey: string
  /** 掩码显示：GFSab****ef */
  accessKeyMasked: string
  remark: string | null
  /** 0正常 1吊销 */
  status: number
  lastUsedAt: string | null
  createdAt: string | null
}

export interface OssAccessKeyCreatedVO {
  id: string
  accessKey: string
  /** 明文仅创建响应返回一次 */
  secretKey: string
  createdAt: string | null
}

/** 当前用户 OSS 密钥列表（不含 Secret Key） */
export function listOssAccessKeys() {
  return request.get<OssAccessKeyVO[]>('/apis/service/oss/keys')
}

/** 创建密钥（Secret Key 明文仅本次返回） */
export function createOssAccessKey(remark?: string) {
  return request.post<OssAccessKeyCreatedVO>('/apis/service/oss/keys', {
    remark: remark || undefined,
  })
}

/** 吊销密钥（立即失效，不可恢复） */
export function revokeOssAccessKey(id: string) {
  return request.delete<void>(`/apis/service/oss/keys/${id}`)
}
