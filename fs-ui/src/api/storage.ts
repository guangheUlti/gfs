import type {
  StoragePlatform,
  StorageSetting,
  ActiveStoragePlatform,
  AddStorageSettingParams,
  StorageFileUsage,
  UpdateStorageSettingParams,
  HostKeyInfo,
} from '@/types/storage'
import { request } from './request'

/**
 * 获取存储平台列表（用于下拉选择）
 */
export function getStoragePlatforms() {
  return request.get<StoragePlatform[]>('/apis/storage/platforms')
}

/**
 * 查询平台当前还可申请的最大配置容量（字节）；平台无容量概念时返回 null。
 * request.get 已解包 Result.data，null 也会原样返回。
 */
export async function getPlatformMaxCapacity(identifier: string) {
  const data = await request.get<number | null>(
    `/apis/storage/platforms/${identifier}/capacity`
  )
  return data ?? null
}

/**
 * 获取用户已配置的存储平台列表
 */
export function getUserStorageSettings() {
  return request.get<StorageSetting[]>('/apis/storage/platform/settings')
}

/**
 * 添加新的存储平台配置
 */
export function addStorageSetting(data: AddStorageSettingParams) {
  return request.post('/apis/storage/settings', data)
}

/**
 * 更新存储平台配置
 */
export function updateStorageSetting(data: UpdateStorageSettingParams) {
  return request.put('/apis/storage/settings', data)
}

/**
 * 查询存储配置的文件占用情况（删除前提示用）；占用统计组件未就绪时返回 null
 */
export async function getStorageFileUsage(id: number) {
  const data = await request.get<StorageFileUsage | null>(
    `/apis/storage/settings/${id}/file-usage`
  )
  return data ?? null
}

/**
 * 删除存储平台配置
 *
 * @param purgeFiles 是否先清理该存储下的全部文件（直读存储只清索引，托管存储连物理文件一并删除）
 */
export function deleteStorageSetting(id: number, purgeFiles = false) {
  return request.delete(`/apis/storage/settings/${id}`, {
    params: { purgeFiles },
  })
}

/**
 * 启用/禁用存储平台配置
 */
export function toggleStorageSetting(id: string, action: number) {
  return request.post(`/apis/storage/settings/${id}/${action}`)
}

/**
 * 获取用户的存储平台配置
 */
export function getStoragePlatformsSettings(identifier: string) {
  return request.get<StorageSetting>(`/apis/storage/settings/${identifier}`)
}

/**
 * 获取当前用户已开通且已配置的存储平台列表
 */
export function getActiveStoragePlatforms() {
  return request.get<ActiveStoragePlatform[]>('/apis/storage/active-platforms')
}

/**
 * 探测存储服务器主机密钥（仅握手，不认证）
 */
export function probeStorageHostKey(
  platformIdentifier: string,
  configData: string
) {
  return request.post<HostKeyInfo>('/apis/storage/host-key/probe', {
    platformIdentifier,
    configData,
  })
}

/**
 * 信任存储服务器主机密钥（复核指纹后写入 known_hosts）
 */
export function trustStorageHostKey(
  platformIdentifier: string,
  configData: string,
  fingerprint: string
) {
  return request.post<HostKeyInfo>('/apis/storage/host-key/trust', {
    platformIdentifier,
    configData,
    fingerprint,
  })
}
