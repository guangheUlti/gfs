import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/** 文件列表默认排序字段：与后端 orderBy 参数取值一致（见 FileListView 的 SortField） */
export type FileSortField = 'displayName' | 'updateTime'

/** 各字段的默认方向：名称 A→Z，修改时间从新到旧 */
export const FILE_SORT_DIRECTION: Record<FileSortField, 'ASC' | 'DESC'> = {
  displayName: 'ASC',
  updateTime: 'DESC',
}

interface AppearanceState {
  /** 是否在文件列表/网格中显示缩略图；纯本机偏好，不进服务端 */
  thumbnailsEnabled: boolean
  setThumbnailsEnabled: (enabled: boolean) => void
  /** 进入文件页时的默认排序字段；纯本机偏好 */
  fileSort: FileSortField
  setFileSort: (field: FileSortField) => void
}

export const useAppearanceStore = create<AppearanceState>()(
  persist(
    (set) => ({
      thumbnailsEnabled: true,
      setThumbnailsEnabled: (thumbnailsEnabled) => set({ thumbnailsEnabled }),
      // 缺省按名称排序，与历史行为一致
      fileSort: 'displayName',
      setFileSort: (fileSort) => set({ fileSort }),
    }),
    { name: 'appearance-storage' }
  )
)
