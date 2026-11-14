import { create } from 'zustand'
import type { FileItem } from '@/types/file'

export type ClipboardMode = 'copy' | 'cut'

interface FileClipboardState {
  mode: ClipboardMode | null
  items: FileItem[]
  /** 剪切中的文件 ID，列表/网格据此置灰 */
  cutIds: string[]
  setClipboard: (mode: ClipboardMode, items: FileItem[]) => void
  clear: () => void
}

/**
 * 文件剪贴板：与系统剪贴板无关，只在页内传递复制/剪切意图。
 * 不做持久化，刷新页面即清空。
 */
export const useFileClipboardStore = create<FileClipboardState>((set) => ({
  mode: null,
  items: [],
  cutIds: [],
  setClipboard: (mode, items) =>
    set({
      mode,
      items,
      cutIds: mode === 'cut' ? items.map((item) => item.id) : [],
    }),
  clear: () => set({ mode: null, items: [], cutIds: [] }),
}))
