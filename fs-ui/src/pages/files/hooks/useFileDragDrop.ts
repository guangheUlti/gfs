import { useState, useCallback, useRef } from 'react'
import {
  hasExternalFiles,
  readDataTransferFiles,
} from '@/utils/data-transfer'
import type { FileItem } from '@/types/file'

export type DragMode = 'move' | 'copy' | 'upload'

export interface DragDropState {
  isDragging: boolean
  draggedItems: FileItem[]
  dropTargetId: string | null
  dropTargetName: string | null
  /** 内页拖拽按住 Ctrl/Cmd 时为复制 */
  copyMode: boolean
  /** 当前拖的是操作系统文件（拖到文件夹行=上传到该目录） */
  external: boolean
}

interface UseFileDragDropOptions {
  onMove: (fileIds: string[], targetDirId: string) => Promise<void>
  /** Ctrl 拖拽复制；缺省时按住 Ctrl 也按移动处理 */
  onCopy?: (fileIds: string[], targetDirId: string) => Promise<void>
  /** 系统文件拖到文件夹行：上传到该文件夹而不是当前目录 */
  onExternalFilesToFolder?: (
    files: File[],
    targetFolder: FileItem
  ) => void | Promise<void>
}

const EMPTY_STATE: DragDropState = {
  isDragging: false,
  draggedItems: [],
  dropTargetId: null,
  dropTargetName: null,
  copyMode: false,
  external: false,
}

export function useFileDragDrop(
  selectedKeys: string[],
  fileList: FileItem[],
  { onMove, onCopy, onExternalFilesToFolder }: UseFileDragDropOptions
) {
  const [dragState, setDragState] = useState<DragDropState>(EMPTY_STATE)
  /** drop 事件的 ctrlKey 不可靠（部分浏览器为 false），dragover 时同步记录 */
  const copyModeRef = useRef(false)

  const resetDragState = useCallback(() => {
    copyModeRef.current = false
    setDragState(EMPTY_STATE)
  }, [])

  /**
   * 开始拖拽
   */
  const handleDragStart = useCallback(
    (e: React.DragEvent, file: FileItem) => {
      // 如果拖拽的文件在选中列表中，拖拽所有选中的文件
      // 否则只拖拽当前文件
      const draggedItems = selectedKeys.includes(file.id)
        ? fileList.filter((f) => selectedKeys.includes(f.id))
        : [file]

      copyModeRef.current = e.ctrlKey || e.metaKey
      setDragState({
        isDragging: true,
        draggedItems,
        dropTargetId: null,
        dropTargetName: null,
        copyMode: copyModeRef.current,
        external: false,
      })

      // 设置拖拽数据
      e.dataTransfer.effectAllowed = 'copyMove'
      e.dataTransfer.setData(
        'application/json',
        JSON.stringify(draggedItems.map((f) => f.id))
      )

      // 创建自定义拖拽图像（克隆元素但去掉背景色）
      const currentElement = e.currentTarget as HTMLElement
      const clone = currentElement.cloneNode(true) as HTMLElement

      // 检查是否是表格行（列表视图）
      const isTableRow = currentElement.tagName === 'TR'

      if (isTableRow) {
        // 列表视图：创建一个包装器来正确显示表格行
        const wrapper = document.createElement('div')
        wrapper.style.cssText = `
          position: absolute;
          top: -10000px;
          left: -10000px;
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 12px 16px;
          background: var(--accent);
          color: var(--accent-foreground);
          border-radius: 8px;
          opacity: 0.95;
          pointer-events: none;
          box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
        `

        // 只提取图标和文件名。按属性而非列序号定位，避免以后增删列时抓错单元格
        const nameCell = clone.querySelector('[data-file-name-cell]')
        if (nameCell) {
          const iconElement = nameCell.querySelector('div > div:first-child')
          const nameElement = nameCell.querySelector('span')

          if (iconElement) {
            const clonedIcon = iconElement.cloneNode(true) as HTMLElement
            wrapper.appendChild(clonedIcon)
          }

          if (nameElement) {
            const clonedName = nameElement.cloneNode(true) as HTMLElement
            clonedName.style.cssText = `
              font-size: 14px;
              color: var(--accent-foreground);
              white-space: nowrap;
            `
            wrapper.appendChild(clonedName)
          }
        }

        document.body.appendChild(wrapper)

        // 如果是多选，添加数量标识
        if (draggedItems.length > 1) {
          const badge = document.createElement('div')
          badge.style.cssText = `
            margin-left: 8px;
            background: #ef4444;
            color: white;
            border-radius: 50%;
            width: 20px;
            height: 20px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 11px;
            font-weight: 700;
            box-shadow: 0 2px 6px rgba(239, 68, 68, 0.4);
          `
          badge.textContent = String(draggedItems.length)
          wrapper.appendChild(badge)
        }

        e.dataTransfer.setDragImage(wrapper, 20, 20)

        // 清理克隆元素
        setTimeout(() => {
          if (document.body.contains(wrapper)) {
            document.body.removeChild(wrapper)
          }
        }, 0)
      } else {
        // 网格视图：原有逻辑
        clone.style.cssText = `
          position: absolute;
          top: -10000px;
          left: -10000px;
          width: ${currentElement.offsetWidth}px;
          height: ${currentElement.offsetHeight}px;
          background: transparent !important;
          border-radius: 8px;
          opacity: 0.9;
          pointer-events: none;
        `

        // 移除克隆元素中所有子元素的背景色
        const allElements = clone.querySelectorAll('*')
        allElements.forEach((el) => {
          ;(el as HTMLElement).style.background = 'transparent'
          ;(el as HTMLElement).style.backgroundColor = 'transparent'
        })

        document.body.appendChild(clone)

        // 如果是多选，添加数量标识（放在文件名右上角）
        if (draggedItems.length > 1) {
          // 找到文件名元素的位置
          const nameElement = clone.querySelector('.text-sm.font-normal')
          if (nameElement) {
            const nameRect = nameElement.getBoundingClientRect()
            const cloneRect = clone.getBoundingClientRect()

            const badge = document.createElement('div')
            badge.style.cssText = `
              position: absolute;
              top: ${nameRect.top - cloneRect.top - 6}px;
              left: ${nameRect.right - cloneRect.left + 4}px;
              background: #ef4444;
              color: white;
              border-radius: 50%;
              width: 20px;
              height: 20px;
              display: flex;
              align-items: center;
              justify-content: center;
              font-size: 11px;
              font-weight: 700;
              box-shadow: 0 2px 6px rgba(239, 68, 68, 0.4);
            `
            badge.textContent = String(draggedItems.length)
            clone.appendChild(badge)
          }
        }

        e.dataTransfer.setDragImage(
          clone,
          currentElement.offsetWidth / 2,
          currentElement.offsetHeight / 2
        )

        // 清理克隆元素
        setTimeout(() => {
          if (document.body.contains(clone)) {
            document.body.removeChild(clone)
          }
        }, 0)
      }
    },
    [selectedKeys, fileList]
  )

  /**
   * 拖拽结束
   */
  const handleDragEnd = useCallback(() => {
    resetDragState()
  }, [resetDragState])

  /**
   * 拖拽进入目标
   */
  const handleDragEnter = useCallback(
    (e: React.DragEvent, targetFolder: FileItem) => {
      // 系统文件：只有文件夹行接管（上传到该目录），其余行放行给容器级上传
      if (hasExternalFiles(e)) {
        if (!targetFolder.isDir) return
        e.preventDefault()
        e.stopPropagation()
        setDragState((prev) =>
          prev.external && prev.dropTargetId === targetFolder.id
            ? prev
            : {
                ...prev,
                dropTargetId: targetFolder.id,
                dropTargetName: targetFolder.displayName,
                external: true,
              }
        )
        return
      }

      e.preventDefault()
      e.stopPropagation()

      // 只有文件夹才能作为拖放目标
      if (!targetFolder.isDir) return

      // 不能拖拽到自己身上
      const draggedIds = dragState.draggedItems.map((f) => f.id)
      if (draggedIds.includes(targetFolder.id)) return

      setDragState((prev) => ({
        ...prev,
        dropTargetId: targetFolder.id,
        dropTargetName: targetFolder.displayName,
      }))
    },
    [dragState.draggedItems]
  )

  /**
   * 拖拽经过目标
   */
  const handleDragOver = useCallback(
    (e: React.DragEvent, targetFolder: FileItem) => {
      if (hasExternalFiles(e)) {
        if (!targetFolder.isDir) return
        e.preventDefault()
        e.stopPropagation()
        e.dataTransfer.dropEffect = 'copy'
        setDragState((prev) =>
          prev.external && prev.dropTargetId === targetFolder.id
            ? prev
            : {
                ...prev,
                dropTargetId: targetFolder.id,
                dropTargetName: targetFolder.displayName,
                external: true,
              }
        )
        return
      }

      e.preventDefault()
      e.stopPropagation()

      // 只有文件夹才能作为拖放目标
      if (!targetFolder.isDir) return

      // 拖动过程中按下/松开 Ctrl 会实时切换复制/移动，与资源管理器一致
      const nextCopy = e.ctrlKey || e.metaKey
      copyModeRef.current = nextCopy
      e.dataTransfer.dropEffect = nextCopy ? 'copy' : 'move'
      setDragState((prev) =>
        prev.copyMode === nextCopy ? prev : { ...prev, copyMode: nextCopy }
      )
    },
    []
  )

  /**
   * 拖拽离开目标
   */
  const handleDragLeave = useCallback(
    (e: React.DragEvent, targetFolder: FileItem) => {
      if (hasExternalFiles(e)) {
        if (!targetFolder.isDir) return
      }

      e.preventDefault()
      e.stopPropagation()

      // 检查是否真的离开了目标元素（而不是进入子元素）
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
      const x = e.clientX
      const y = e.clientY

      if (
        x < rect.left ||
        x >= rect.right ||
        y < rect.top ||
        y >= rect.bottom
      ) {
        setDragState((prev) =>
          prev.dropTargetId === targetFolder.id
            ? {
                ...prev,
                dropTargetId: null,
                dropTargetName: null,
                external: false,
              }
            : prev
        )
      }
    },
    []
  )

  /**
   * 放置到目标
   */
  const handleDrop = useCallback(
    async (e: React.DragEvent, targetFolder: FileItem) => {
      // 系统文件拖到文件夹行：上传到该文件夹；非文件夹行交给容器级上传（当前目录）
      if (hasExternalFiles(e)) {
        if (!targetFolder.isDir) return
        e.preventDefault()
        e.stopPropagation()
        const files = await readDataTransferFiles(e.dataTransfer)
        resetDragState()
        if (files.length > 0 && onExternalFilesToFolder) {
          await onExternalFilesToFolder(files, targetFolder)
        }
        return
      }

      e.preventDefault()
      e.stopPropagation()

      // 只有文件夹才能作为拖放目标
      if (!targetFolder.isDir) return

      try {
        const fileIdsJson = e.dataTransfer.getData('application/json')
        if (!fileIdsJson) return

        const fileIds = JSON.parse(fileIdsJson) as string[]

        // 不能拖拽到自己身上
        if (fileIds.includes(targetFolder.id)) return

        const isCopy = e.ctrlKey || e.metaKey || copyModeRef.current
        if (isCopy && onCopy) {
          await onCopy(fileIds, targetFolder.id)
        } else {
          await onMove(fileIds, targetFolder.id)
        }
      } catch (error) {
        console.error('拖拽操作失败:', error)
      } finally {
        resetDragState()
      }
    },
    [onMove, onCopy, onExternalFilesToFolder, resetDragState]
  )

  return {
    dragState,
    handleDragStart,
    handleDragEnd,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  }
}
