import { create } from 'zustand'
import { featureApi } from '@/api/feature'

/**
 * 全局功能开关状态：决定收藏、历史等入口在侧边栏是否显示。
 * 未加载或接口未返回的功能一律视为关闭。
 */
interface FeatureState {
  toggles: Record<string, boolean>
  loaded: boolean
  fetchToggles: () => Promise<void>
  setToggle: (key: string, value: boolean) => void
  reset: () => void
}

export const useFeatureStore = create<FeatureState>((set) => ({
  toggles: {},
  loaded: false,

  fetchToggles: async () => {
    try {
      const toggles = await featureApi.getToggles()
      set({ toggles: toggles ?? {}, loaded: true })
    } catch {
      set({ loaded: true })
    }
  },

  setToggle: (key, value) => {
    set((state) => ({ toggles: { ...state.toggles, [key]: value } }))
  },

  reset: () => {
    set({ toggles: {}, loaded: false })
  },
}))
