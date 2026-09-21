import { create } from 'zustand'
import type { FileItem } from '@/types/file'

/**
 * 文件页位置记忆：离开 /files（全部文件视图）时保存当前目录查询串与列表快照，
 * 从其他页面（如传输页）点击侧边栏「文件」回到裸 /files 时恢复现场。
 *
 * - locationSearch：目录位置查询串（parentId/view/viewMode/type/isDir/keyword），
 *   URL 是位置的唯一权威，恢复 = 重定向回该查询串（router 的 filesLoader 消费）；
 * - snapshot：卸载前的文件列表，恢复目标查询串与之一致时先渲染快照再后台静默校准，
 *   避免重复请求造成 loading 闪烁。
 */
interface FilesLocationState {
  /** 最近一次离开 /files 时的 location.search（不含 ?），空串表示无记忆 */
  locationSearch: string
  /** 快照对应的查询串，仅当恢复目标与之一致时才可复用 */
  snapshotSearch: string
  snapshotFiles: FileItem[]
  snapshotTotal: number

  saveLocation: (payload: {
    search: string
    files: FileItem[]
    total: number
  }) => void
  /** 仅记忆位置（列表尚未加载完成/为空时），保留已有快照 */
  saveLocationOnly: (search: string) => void
  /** 消费位置记忆（loader 重定向时调用），快照保留供挂载恢复 */
  clearLocation: () => void
  /** 按查询串取快照（仅精确匹配），取出即清除防止复用过期数据 */
  takeSnapshot: (
    search: string
  ) => { files: FileItem[]; total: number } | null
  /** 全部清空（登出时调用，避免跨账号恢复） */
  clear: () => void
}

export const useFilesLocationStore = create<FilesLocationState>((set, get) => ({
  locationSearch: '',
  snapshotSearch: '',
  snapshotFiles: [],
  snapshotTotal: 0,

  saveLocation: ({ search, files, total }) =>
    set({
      locationSearch: search,
      snapshotSearch: search,
      snapshotFiles: files,
      snapshotTotal: total,
    }),

  saveLocationOnly: (search) => set({ locationSearch: search }),

  clearLocation: () => set({ locationSearch: '' }),

  takeSnapshot: (search) => {
    const { snapshotSearch, snapshotFiles, snapshotTotal } = get()
    if (
      !snapshotSearch ||
      snapshotSearch !== search ||
      snapshotFiles.length === 0
    ) {
      return null
    }
    const snapshot = { files: snapshotFiles, total: snapshotTotal }
    set({ snapshotSearch: '', snapshotFiles: [], snapshotTotal: 0 })
    return snapshot
  },

  clear: () =>
    set({
      locationSearch: '',
      snapshotSearch: '',
      snapshotFiles: [],
      snapshotTotal: 0,
    }),
}))
