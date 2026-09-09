import { create } from 'zustand'
import type { FileItem } from '@/types/file'

interface PreviewStoreState {
  previewState: { files: FileItem[]; index: number; edit?: boolean } | null
  openPreview: (files: FileItem[], index: number, options?: { edit?: boolean }) => void
  closePreview: () => void
  stepPreview: (delta: number) => void
  switchPreview: (index: number) => void
}

export const usePreviewStore = create<PreviewStoreState>()((set) => ({
  previewState: null,
  openPreview: (files, index, options) =>
    set({ previewState: { files, index, edit: options?.edit === true } }),
  closePreview: () => set({ previewState: null }),
  stepPreview: (delta) =>
    set((state) => {
      if (!state.previewState) return state
      const { index } = state.previewState
      const next = Math.min(Math.max(index + delta, 0), state.previewState.files.length - 1)
      return { previewState: { ...state.previewState, index: next } }
    }),
  switchPreview: (index) =>
    set((state) => {
      if (!state.previewState) return state
      return { previewState: { ...state.previewState, index } }
    }),
}))
