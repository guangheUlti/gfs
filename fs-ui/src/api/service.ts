import { request } from './request'

export interface ServiceSettingVO {
  serviceType: 'webdav' | 'sftp' | 'ftp'
  enabled: boolean
  /** socket 型服务（sftp/ftp）有值；webdav 复用 HTTP 80 端口为 null */
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
