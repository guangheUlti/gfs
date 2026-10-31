import { request } from './request'

export interface ServiceSettingVO {
  serviceType: 'webdav' | 'alist' | 'sftp' | 'ftp' | 'oss'
  enabled: boolean
  /** 只读模式（四条 HTTP/协议端均支持）：开启后拒绝一切写操作 */
  readOnly: boolean
  /** 游客访问（仅 alist）：未登录的播放器可匿名浏览/播放 */
  allowGuest: boolean
  /** 游客视角账号 ID（仅 alist）：匿名请求以该账号身份只读 */
  guestUserId: string | null
  /** 游客视角账号用户名（仅 alist） */
  guestUsername: string | null
  /** 服务级存储配置 ID（全部协议）：外部客户端固定访问该存储，Local 表示内置系统存储 */
  storageSettingId: string
  /** socket 型服务（sftp/ftp）有值；webdav/alist/oss 复用主 HTTP 端口为 null */
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
  /** 全部协议，缺省表示不修改 */
  readOnly?: boolean
  /** 仅 AList，缺省表示不修改 */
  allowGuest?: boolean
  /** 仅 AList，传空串表示清除绑定 */
  guestUserId?: string
  /** 全部协议：服务级存储配置 ID，Local 或空串表示内置系统存储，缺省表示不修改 */
  storageSettingId?: string
  /** 仅 SFTP，1024-65535 */
  port?: number
  /** 仅 SFTP */
  bindAddress?: string
}

export interface ServiceGuestUserVO {
  id: string
  username: string
  nickname: string | null
}

/** 可被选为游客视角的账号（仅正常状态用户） */
export function listAlistGuestUsers() {
  return request.get<ServiceGuestUserVO[]>('/apis/service/alist/users')
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

/** 永久删除已吊销的密钥记录（仅清理列表，不能代替吊销） */
export function purgeOssAccessKey(id: string) {
  return request.delete<void>(`/apis/service/oss/keys/${id}/purge`)
}
