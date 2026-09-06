import dayjs from 'dayjs'

import type { HomeUsedBytesUnit } from '@/api/home'

export const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`
}

const compactZh = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    notation: 'compact',
    maximumFractionDigits: 2,
  }).format(value)

const GB_BYTES = 1024 ** 3
const TB_BYTES = 1024 ** 4

/**
 * 存储容量文案：≥ 1TB 按 T 显示，否则按 G 显示，统一保留 1 位小数。
 * 不再细分 B/KB/MB，避开侧边栏出现「0.00 B」这类无信息量的文案。
 */
export function formatCapacityBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) {
    return '—'
  }
  if (bytes < 0) return '—'
  if (bytes >= TB_BYTES) return `${(bytes / TB_BYTES).toFixed(1)} TB`
  return `${(bytes / GB_BYTES).toFixed(1)} GB`
}

/**
 * 与首页存储图表纵轴/Tooltip 一致：万级及以上 KB/MB/GB 均用紧凑（如 2.16万）；
 * KB 未过万也保持紧凑；MB/GB 较小值按数量级保留小数。不做单位换算。
 */
export function formatHomeStorageNumber(
  value: number,
  unit: HomeUsedBytesUnit
): string {
  if (!Number.isFinite(value)) return ''
  if (value === 0) return '0'
  const abs = Math.abs(value)

  if (abs >= 10000) {
    return compactZh(value)
  }
  if (unit === 1) {
    return compactZh(value)
  }
  if (abs >= 100) {
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  }
  if (abs >= 1) {
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
  }
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: 8,
    minimumFractionDigits: 0,
  }).format(value)
}

/** 首页存储概览：数字格式与图表一致，再拼服务端单位文案 */
export function formatHomeStorageDisplay(
  value: number,
  unitLabel: string,
  storageUnit: HomeUsedBytesUnit
): string {
  if (!Number.isFinite(value)) return '—'
  const num = formatHomeStorageNumber(value, storageUnit)
  const u = unitLabel.trim()
  return u ? `${num} ${u}` : num
}

export const formatDate = (
  date: string | number | Date,
  format = 'YYYY/MM/DD HH:mm:ss'
): string => {
  return dayjs(date).format(format)
}

export const formatFileTime = (date: string | number | Date): string => {
  return dayjs(date).format('YYYY/MM/DD HH:mm:ss')
}

/**
 * 文件列表行：固定 yyyy-MM-dd HH:mm，显示到分钟。
 * 这里刻意不做「今天」这类相对表述——列表按时间排序时绝对日期更好比对，
 * 且列宽恒定（16 字符），窄屏不会因为文案长度变化而抖动。
 */
export function formatFileListDisplayTime(
  dateStr: string | number | Date
): string {
  const date = new Date(dateStr)

  const year = date.getFullYear()
  const month = (date.getMonth() + 1).toString().padStart(2, '0')
  const day = date.getDate().toString().padStart(2, '0')
  const hours = date.getHours().toString().padStart(2, '0')
  const minutes = date.getMinutes().toString().padStart(2, '0')

  return `${year}-${month}-${day} ${hours}:${minutes}`
}

export const formatDuration = (seconds: number): string => {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = Math.floor(seconds % 60)

  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
  }
  return `${minutes}:${secs.toString().padStart(2, '0')}`
}
