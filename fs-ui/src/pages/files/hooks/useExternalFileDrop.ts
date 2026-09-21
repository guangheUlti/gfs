import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useTransferStore } from '@/store/transfer'
import { navigateToTransferIfEnabled } from '@/services/auto-navigate'
import {
  hasExternalFiles,
  readDataTransferFiles,
} from '@/utils/data-transfer'

interface UseExternalFileDropOptions {
  /** 无写权限时整体不响应 */
  enabled: boolean
  /** 上传目标目录 id */
  parentId?: string
}

/**
 * 操作系统文件/文件夹拖入上传。
 *
 * 事件挂在文件区域容器上，与页内拖拽移动（useFileDragDrop）互不干扰：
 * 系统拖入的 dataTransfer.types 含 'Files'，页内拖拽只有 'application/json'。
 * 页内拖拽的行级 handler 对系统拖入直接放行（见 useFileDragDrop），
 * 事件才能冒泡到这里被 preventDefault 并触发 drop。
 *
 * 拖入文件夹时递归读取全部文件并保留目录结构，交给
 * createTasksWithDirectory 重建目录（数量/大小/深度限制由 store 内负责提示）。
 */
export function useExternalFileDrop({
  enabled,
  parentId,
}: UseExternalFileDropOptions) {
  const { t } = useTranslation('files')
  const [isDragging, setIsDragging] = useState(false)
  const { startUploadSession, createTask, createTasksWithDirectory } =
    useTransferStore()

  const handleDragEnter = useCallback(
    (e: React.DragEvent) => {
      if (!enabled || !hasExternalFiles(e)) return
      e.preventDefault()
      setIsDragging(true)
    },
    [enabled]
  )

  // dragover 必须持续 preventDefault，浏览器才允许 drop 并显示复制光标
  const handleDragOver = useCallback(
    (e: React.DragEvent) => {
      if (!enabled || !hasExternalFiles(e)) return
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
    },
    [enabled]
  )

  // 子元素的 dragleave 会冒泡上来，用坐标判断是否真的离开了容器边界
  const handleDragLeave = useCallback(
    (e: React.DragEvent) => {
      if (!enabled || !hasExternalFiles(e)) return
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
      const { clientX: x, clientY: y } = e
      if (
        x < rect.left ||
        x >= rect.right ||
        y < rect.top ||
        y >= rect.bottom
      ) {
        setIsDragging(false)
      }
    },
    [enabled]
  )

  const handleDrop = useCallback(
    async (e: React.DragEvent) => {
      if (!enabled || !hasExternalFiles(e)) return
      e.preventDefault()
      setIsDragging(false)

      const files = await readDataTransferFiles(e.dataTransfer)
      if (files.length === 0) return

      // 相对路径带目录前缀（拖入文件夹）时按目录上传；纯顶层文件直接建任务
      const hasDirectory = files.some(
        (f) => f.webkitRelativePath && f.webkitRelativePath !== f.name
      )

      startUploadSession()
      try {
        if (hasDirectory) {
          await createTasksWithDirectory(files, parentId)
        } else {
          await Promise.all(files.map((file) => createTask(file, parentId)))
        }
      } catch {
        // 超限等错误由 transfer store 内部 toast 提示，这里不重复
        return
      }

      toast.success(t('operations.uploadFileAdded'), {
        description: t('operations.uploadCheckProgress'),
      })
      // 传输设置开了「自动跳转」时前往传输页；否则留在文件页
      navigateToTransferIfEnabled()
    },
    [
      enabled,
      parentId,
      startUploadSession,
      createTask,
      createTasksWithDirectory,
      t,
    ]
  )

  return {
    isDragging,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  }
}
