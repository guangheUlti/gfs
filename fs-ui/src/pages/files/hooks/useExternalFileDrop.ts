import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useTransferStore } from '@/store/transfer'
import {
  beginTransferLaunch,
  endTransferLaunch,
  navigateBackFromAutoTransferIfIdle,
  navigateToTransferIfEnabled,
} from '@/services/auto-navigate'
import {
  hasExternalFiles,
  readDataTransferFiles,
} from '@/utils/data-transfer'
import { useUploadConflictPrompt } from './useUploadConflictPrompt'

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
  const { resolveUploadConflicts, conflictDialog } = useUploadConflictPrompt()

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

  /**
   * 上传一组系统文件到指定目录（当前目录或拖到的文件夹行）。
   */
  const uploadFilesTo = useCallback(
    async (files: File[], targetParentId?: string) => {
      if (files.length === 0) return

      // 同名冲突先问策略再跳转/建任务：用户取消时不该留下空会话或空列表
      const conflictDecision = await resolveUploadConflicts(
        targetParentId,
        files
      )
      if (conflictDecision === null) return

      // 相对路径带目录前缀（拖入文件夹）时按目录上传；纯顶层文件直接建任务
      const hasDirectory = files.some(
        (f) => f.webkitRelativePath && f.webkitRelativePath !== f.name
      )

      // 先跳转再建任务：拖入文件夹要逐层建目录、再逐个初始化上传，耗时可达数秒，
      // 放在建任务之后跳会让用户以为没反应；中途某个文件初始化失败还会整个跳过跳转。
      // 本批全部传完后由 navigateBackFromAutoTransferIfIdle 自动回到文件页
      navigateToTransferIfEnabled('uploading')

      startUploadSession()
      // 建任务期间（尤其拖文件夹要逐层建目录）任务队列会短暂为空，
      // 期间禁止「全部完成→自动回文件页」，否则刚跳过去就被弹回来
      beginTransferLaunch()
      let failed = false
      try {
        if (hasDirectory) {
          await createTasksWithDirectory(
            files,
            targetParentId,
            conflictDecision
          )
        } else {
          await Promise.all(
            files.map((file) =>
              createTask(
                file,
                targetParentId,
                undefined,
                conflictDecision.policy
              )
            )
          )
        }
      } catch {
        failed = true
      } finally {
        endTransferLaunch()
      }
      if (failed) {
        // 超限等错误由 transfer store 内部 toast 提示，这里不重复；
        // 前面已经跳转，若一个任务都没建出来就退回文件页，别让用户停在空列表
        navigateBackFromAutoTransferIfIdle()
        return
      }

      toast.success(t('operations.uploadFileAdded'), {
        description: t('operations.uploadCheckProgress'),
      })
    },
    [
      startUploadSession,
      createTask,
      createTasksWithDirectory,
      resolveUploadConflicts,
      t,
    ]
  )

  const handleDrop = useCallback(
    async (e: React.DragEvent) => {
      if (!enabled || !hasExternalFiles(e)) return
      e.preventDefault()
      setIsDragging(false)

      const files = await readDataTransferFiles(e.dataTransfer)
      await uploadFilesTo(files, parentId)
    },
    [enabled, parentId, uploadFilesTo]
  )

  return {
    isDragging,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    uploadFilesTo,
    conflictDialog,
  }
}
