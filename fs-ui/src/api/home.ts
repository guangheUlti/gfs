import type { FileItem } from '@/types/file'
import { request } from './request'

/** 首页 getHomeInfo 定时刷新间隔（毫秒）；标签页失焦时 React Query 默认会暂停轮询 */
export const HOME_INFO_REFETCH_INTERVAL_MS = 60_000

/** 与后端 @Schema 一致：1 KB, 2 MB, 3 GB（无字节） */
export type HomeUsedBytesUnit = 1 | 2 | 3

/** 0 近三个月, 1 近 30 天, 2 近 7 天 */
export type HomeUsedBytesDateType = 0 | 1 | 2

export interface HomeUsedBytesPoint {
  date: string
  usedBytes: number
}

/** 对应 FileHomeVO：合并后的首页信息 */
export interface HomeInfo {
  /** 与 unit 配套，已为展示用数值，无需再按字节换算 */
  usedStorage: number
  /** 与 usedStorage、usedBytes 数值配套的单位文案，由服务端返回 */
  unit?: string
  /** 随 unit、dateType 查询条件变化 */
  usedBytes: HomeUsedBytesPoint[]
  recentFiles: FileItem[]
}

export function getHomeInfo(params?: {
  unit?: HomeUsedBytesUnit
  dateType?: HomeUsedBytesDateType
}) {
  return request.get<HomeInfo>('/apis/home/info', { params })
}

/** 存储容量轮询间隔（毫秒）：磁盘容量变化很慢，比首页概览更宽松 */
export const STORAGE_CAPACITY_REFETCH_INTERVAL_MS = 120_000

/** 对应 StorageCapacityVO：总量 = 存储底座剩余可写空间 + 本系统已存文件占用 */
export interface StorageCapacity {
  /** 已使用字节数，即本系统已存文件占用 */
  usedBytes: number | null
  /** 剩余可写字节数；容量不可知时为 null */
  availableBytes: number | null
  /** 总字节数（剩余可写 + 已使用）；容量不可知时为 null */
  totalBytes: number | null
  /** 对象存储无本地磁盘概念时为 false，此时只展示已使用量 */
  capacityKnown: boolean | null
}

export function getStorageCapacity() {
  return request.get<StorageCapacity>('/apis/home/storage/capacity')
}

/** 磁盘分区信息 */
export interface DiskPartition {
  /** 挂载点/盘符，如 C:\ 或 / */
  mountPoint: string
  totalBytes: number
  usedBytes: number
  freeBytes: number
}

/** 对应 SystemInfoVO：系统与运行信息，任一项采集失败仅该字段为 null */
export interface SystemInfo {
  osName: string | null
  osArch: string | null
  cpuCores: number | null
  processCpuLoad: number | null
  systemCpuLoad: number | null
  jvmUsedBytes: number | null
  jvmMaxBytes: number | null
  jvmCommittedBytes: number | null
  javaVersion: string | null
  startTime: string | null
  uptimeText: string | null
  uptimeMillis: number | null
  storagePath: string | null
  storageType: string | null
  disks: DiskPartition[] | null
}

export function getSystemInfo() {
  return request.get<SystemInfo>('/apis/home/system/info')
}
