import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getActiveStoragePlatforms } from '@/api/storage'

/** 当前存储平台选择（localStorage）；未写入 = 内置本地存储（请求头不携带 configId） */
export const STORAGE_PLATFORM_KEY = 'current-storage-platform'
const STORAGE_PLATFORM_CHANGED = 'storage-platform-changed'

export function readCurrentStorageSettingId(): string {
  try {
    const stored = JSON.parse(
      localStorage.getItem(STORAGE_PLATFORM_KEY) || 'null'
    )
    if (stored?.settingId) {
      return stored.settingId as string
    }
  } catch {
    // 本地数据异常按未选择处理
  }
  return 'Local'
}

/** 切换存储后通知同页组件（localStorage 自身不会触发同窗口重渲染） */
export function notifyStoragePlatformChanged() {
  window.dispatchEvent(new Event(STORAGE_PLATFORM_CHANGED))
}

export function useActiveStoragePlatforms() {
  return useQuery({
    queryKey: ['activeStoragePlatforms'],
    queryFn: getActiveStoragePlatforms,
    staleTime: 30_000,
  })
}

/**
 * 当前存储平台及其能力。
 *
 * isStatelessRemote：无状态远程（SFTP/SMB/FTP/WebDAV/内存/本地挂载），文件直接操作远端、不落库，
 * 因此回收站/收藏/最近使用都不可用。
 */
export function useCurrentStoragePlatform() {
  const [settingId, setSettingId] = useState(readCurrentStorageSettingId)

  useEffect(() => {
    const sync = () => setSettingId(readCurrentStorageSettingId())
    window.addEventListener(STORAGE_PLATFORM_CHANGED, sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener(STORAGE_PLATFORM_CHANGED, sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const { data: platforms = [] } = useActiveStoragePlatforms()
  const platform = platforms.find((p) => p.settingId === settingId) ?? null

  return {
    settingId,
    platform,
    isStateless: !!platform?.statelessRemote,
  }
}
