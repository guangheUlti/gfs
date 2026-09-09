import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AppearanceState {
  /** 是否在文件列表/网格中显示缩略图；纯本机偏好，不进服务端 */
  thumbnailsEnabled: boolean
  setThumbnailsEnabled: (enabled: boolean) => void
}

export const useAppearanceStore = create<AppearanceState>()(
  persist(
    (set) => ({
      thumbnailsEnabled: true,
      setThumbnailsEnabled: (thumbnailsEnabled) => set({ thumbnailsEnabled }),
    }),
    { name: 'appearance-storage' }
  )
)
