import { useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { isHandledError } from '@/api/request'

/** 一次可撤销的文件操作；undo/redo 都需自行刷新列表 */
export interface UndoAction {
  /** 已本地化的操作名，用于提示文案 */
  label: string
  undo: () => Promise<void>
  redo: () => Promise<void>
}

export type RecordUndo = (action: UndoAction) => void

const MAX_UNDO_STACK = 50

/**
 * 文件操作的撤销/重做栈。
 * 只记录本页发起的操作（重命名、移动、复制、删除到回收站、新建），
 * 结构简单到不需要持久化，切换目录或刷新页面后仍按栈顺序生效。
 */
export function useFileUndo() {
  const { t } = useTranslation('files')
  const undoStackRef = useRef<UndoAction[]>([])
  const redoStackRef = useRef<UndoAction[]>([])
  const pendingRef = useRef(false)

  const recordUndo = useCallback<RecordUndo>((action) => {
    undoStackRef.current.push(action)
    if (undoStackRef.current.length > MAX_UNDO_STACK) {
      undoStackRef.current.shift()
    }
    // 新操作让旧的「重做」分支失效，与 Windows 一致
    redoStackRef.current = []
  }, [])

  const undo = useCallback(async () => {
    if (pendingRef.current) return
    const action = undoStackRef.current.pop()
    if (!action) return
    pendingRef.current = true
    try {
      await action.undo()
      redoStackRef.current.push(action)
      toast.success(t('undo.undoOk', { action: action.label }))
    } catch (error) {
      // 服务端错误拦截器已提示，这里只兜底静默失败
      if (!isHandledError(error)) {
        toast.error(t('undo.undoFail', { action: action.label }))
      }
    } finally {
      pendingRef.current = false
    }
  }, [t])

  const redo = useCallback(async () => {
    if (pendingRef.current) return
    const action = redoStackRef.current.pop()
    if (!action) return
    pendingRef.current = true
    try {
      await action.redo()
      undoStackRef.current.push(action)
      toast.success(t('undo.redoOk', { action: action.label }))
    } catch (error) {
      if (!isHandledError(error)) {
        toast.error(t('undo.redoFail', { action: action.label }))
      }
    } finally {
      pendingRef.current = false
    }
  }, [t])

  return { recordUndo, undo, redo }
}
