import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import type { FileItem } from '@/types/file'
import { toast } from 'sonner'
import {
  deleteFiles,
  permanentlyDeleteFiles,
  renameFile,
  moveFiles,
  createFolder,
  createTextFile,
  favoriteFile,
  unfavoriteFile,
} from '@/api/file'
import { createDirectLink } from '@/api/share'
import { usePreviewStore } from '@/store/preview'
import { useTransferStore } from '@/store/transfer'
import { useFeatureStore } from '@/store/feature'

export function useFileOperations(
  refreshCallback: () => void,
  clearSelectionCallback?: () => void,
  onCreateFolderSuccess?: () => void,
  updateFileItemsCallback?: (ids: string[], patch: Partial<FileItem>) => void
) {
  const { t } = useTranslation('files')
  // 模态框状态
  const [createFolderModalVisible, setCreateFolderModalVisible] =
    useState(false)
  const [createTextModalVisible, setCreateTextModalVisible] = useState(false)
  // 当前新建文本的类型（txt/json/md），由菜单入口决定
  const [createTextSuffix, setCreateTextSuffix] = useState('txt')
  const [renameModalVisible, setRenameModalVisible] = useState(false)
  const [moveModalVisible, setMoveModalVisible] = useState(false)
  const [shareModalVisible, setShareModalVisible] = useState(false)
  const [deleteDialogVisible, setDeleteDialogVisible] = useState(false)
  const [permanentDeleteDialogVisible, setPermanentDeleteDialogVisible] =
    useState(false)
  const [detailModalVisible, setDetailModalVisible] = useState(false)

  // 操作的文件
  const [renamingFile, setRenamingFile] = useState<FileItem | null>(null)
  const [movingFile, setMovingFile] = useState<FileItem | null>(null)
  const [movingFiles, setMovingFiles] = useState<FileItem[]>([])
  const [sharingFile, setSharingFile] = useState<FileItem | null>(null)
  const [sharingFiles, setSharingFiles] = useState<FileItem[]>([])
  const [deletingFiles, setDeletingFiles] = useState<FileItem[]>([])
  const [permanentlyDeletingFiles, setPermanentlyDeletingFiles] = useState<
    FileItem[]
  >([])
  const [detailFile, setDetailFile] = useState<FileItem | null>(null)

  /**
   * 打开创建文件夹弹窗
   */
  const openCreateFolderModal = useCallback(() => {
    setCreateFolderModalVisible(true)
  }, [])

  /**
   * 创建文件夹
   */
  const handleCreateFolder = useCallback(
    async (folderName: string, parentId?: string) => {
      try {
        await createFolder({ folderName: folderName.trim(), parentId })
        toast.success(t('operations.mkdirOk'))
        setCreateFolderModalVisible(false)
        onCreateFolderSuccess?.()
        refreshCallback()
      } catch (error) {
        toast.error(t('operations.mkdirFail'))
      }
    },
    [refreshCallback, onCreateFolderSuccess, t]
  )

  /**
   * 打开新建文本弹窗（suffix 由菜单入口决定：txt/json/md）
   */
  const openCreateTextModal = useCallback((suffix: string = 'txt') => {
    setCreateTextSuffix(suffix)
    setCreateTextModalVisible(true)
  }, [])

  /**
   * 新建文本文件
   */
  const handleCreateText = useCallback(
    async (fileName: string, parentId?: string) => {
      try {
        await createTextFile({ fileName, suffix: createTextSuffix, parentId })
        toast.success(t('operations.createTextOk'))
        setCreateTextModalVisible(false)
        onCreateFolderSuccess?.()
        refreshCallback()
      } catch (error) {
        toast.error(t('operations.createTextFail'))
      }
    },
    [refreshCallback, onCreateFolderSuccess, t, createTextSuffix]
  )

  /**
   * 在线编辑（文本/代码/Markdown 类）：复用预览弹窗，直接进入编辑模式
   */
  const openTextEditor = useCallback((file: FileItem) => {
    usePreviewStore.getState().openPreview([file], 0, { edit: true })
  }, [])

  /**
   * 打开重命名弹窗
   */
  const openRenameModal = useCallback((file: FileItem) => {
    setRenamingFile(file)
    setRenameModalVisible(true)
  }, [])

  /**
   * 重命名文件
   */
  const handleRename = useCallback(
    async (fileId: string, newName: string) => {
      try {
        await renameFile(fileId, newName.trim())
        toast.success(t('operations.renameOk'))
        setRenameModalVisible(false)
        setRenamingFile(null)
        clearSelectionCallback?.()
        refreshCallback()
      } catch (error) {
        toast.error(t('operations.renameFail'))
      }
    },
    [refreshCallback, clearSelectionCallback, t]
  )

  /**
   * 打开移动文件弹窗
   */
  const openMoveModal = useCallback((file: FileItem) => {
    setMovingFile(file)
    setMovingFiles([file])
    setMoveModalVisible(true)
  }, [])

  /**
   * 打开批量移动弹窗
   */
  const openBatchMoveModal = useCallback((files: FileItem[]) => {
    setMovingFile(null)
    setMovingFiles(files)
    setMoveModalVisible(true)
  }, [])

  /**
   * 移动文件
   */
  const handleMove = useCallback(
    async (fileIds: string[], targetDirId: string) => {
      try {
        await moveFiles(targetDirId, fileIds)
        toast.success(t('operations.moveOk'))
        setMoveModalVisible(false)
        setMovingFile(null)
        setMovingFiles([])
        clearSelectionCallback?.()
        refreshCallback()
      } catch (error) {
        toast.error(t('operations.moveFail'))
      }
    },
    [refreshCallback, clearSelectionCallback, t]
  )

  /**
   * 打开分享弹窗
   */
  const openShareModal = useCallback((file: FileItem) => {
    setSharingFile(file)
    setSharingFiles([file])
    setShareModalVisible(true)
  }, [])

  /**
   * 打开批量分享弹窗
   */
  const openBatchShareModal = useCallback((files: FileItem[]) => {
    setSharingFile(null)
    setSharingFiles(files)
    setShareModalVisible(true)
  }, [])

  /**
   * 生成/获取直链并复制到剪贴板（免登录下载链接）
   */
  const copyDirectLink = useCallback(
    async (file: FileItem) => {
      try {
        const res = await createDirectLink({ fileId: file.id, expireType: 4 })
        const fullUrl = `${window.location.origin}${res.directUrl}`
        await navigator.clipboard.writeText(fullUrl)
        toast.success(t('operations.copyDirectLinkOk'))
      } catch (error) {
        toast.error(t('operations.copyDirectLinkFail'))
      }
    },
    [t]
  )

  /** 回收站开关是否开启（缺省关闭，与后端缺省一致） */
  const isRecycleEnabled = useCallback(
    () => !!useFeatureStore.getState().toggles.recycleBin,
    []
  )

  /**
   * 删除文件：回收站开启→移入回收站；关闭→直接永久删除
   */
  const handleDelete = useCallback(async () => {
    const fileIds = deletingFiles.map((f) => f.id)
    const recycleEnabled = isRecycleEnabled()
    try {
      if (recycleEnabled) {
        await deleteFiles(fileIds)
      } else {
        await permanentlyDeleteFiles(fileIds)
      }
      const successMsg = recycleEnabled
        ? fileIds.length === 1
          ? t('operations.trashOne')
          : t('operations.trashMany', { count: fileIds.length })
        : fileIds.length === 1
          ? t('operations.deleteForeverOne')
          : t('operations.deleteForeverMany', { count: fileIds.length })
      toast.success(successMsg)
      setDeleteDialogVisible(false)
      setDeletingFiles([])
      clearSelectionCallback?.()
      refreshCallback()
    } catch (error) {
      toast.error(recycleEnabled ? t('operations.trashFail') : t('operations.deleteForeverFail'))
    }
  }, [deletingFiles, refreshCallback, clearSelectionCallback, t, isRecycleEnabled])

  /**
   * 打开删除确认对话框
   * 回收站关闭时改开「永久删除」确认框（不可恢复警告），行为与后端分流一致
   */
  const openDeleteConfirm = useCallback(
    (file: FileItem) => {
      if (isRecycleEnabled()) {
        setDeletingFiles([file])
        setDeleteDialogVisible(true)
      } else {
        setPermanentlyDeletingFiles([file])
        setPermanentDeleteDialogVisible(true)
      }
    },
    [isRecycleEnabled]
  )

  /**
   * 打开批量删除确认对话框
   */
  const openBatchDeleteConfirm = useCallback(
    (files: FileItem[]) => {
      if (isRecycleEnabled()) {
        setDeletingFiles(files)
        setDeleteDialogVisible(true)
      } else {
        setPermanentlyDeletingFiles(files)
        setPermanentDeleteDialogVisible(true)
      }
    },
    [isRecycleEnabled]
  )

  /**
   * 永久删除文件（不经回收站，不可恢复）
   */
  const handlePermanentDelete = useCallback(async () => {
    const fileIds = permanentlyDeletingFiles.map((f) => f.id)
    try {
      await permanentlyDeleteFiles(fileIds)
      const successMsg =
        fileIds.length === 1
          ? t('operations.deleteForeverOne')
          : t('operations.deleteForeverMany', { count: fileIds.length })
      toast.success(successMsg)
      setPermanentDeleteDialogVisible(false)
      setPermanentlyDeletingFiles([])
      clearSelectionCallback?.()
      refreshCallback()
    } catch (error) {
      toast.error(t('operations.deleteForeverFail'))
    }
  }, [permanentlyDeletingFiles, refreshCallback, clearSelectionCallback, t])

  /**
   * 下载文件：走任务式分片下载，进度在「传输列表-下载中」展示
   */
  const handleDownload = useCallback((files: FileItem | FileItem[]) => {
    const fileArray = Array.isArray(files) ? files : [files]
    void useTransferStore.getState().createDownloadTasks(fileArray)
  }, [])

  /**
   * 收藏/取消收藏
   * 使用乐观更新：直接修改本地状态，失败时回滚刷新列表
   */
  const handleFavorite = useCallback(
    async (files: FileItem | FileItem[]) => {
      const fileArray = Array.isArray(files) ? files : [files]
      const fileIds = fileArray.map((f) => f.id)

      // 判断是收藏还是取消收藏（如果有任何一个未收藏，就执行收藏操作）
      const hasUnfavorited = fileArray.some((f) => !f.isFavorite)
      const newFavoriteState = hasUnfavorited

      // 乐观更新本地状态
      updateFileItemsCallback?.(fileIds, { isFavorite: newFavoriteState })
      clearSelectionCallback?.()

      try {
        if (hasUnfavorited) {
          await favoriteFile(fileIds)
          toast.success(t('operations.favOk'))
        } else {
          await unfavoriteFile(fileIds)
          toast.success(t('operations.unfavOk'))
        }
      } catch (error) {
        toast.error(hasUnfavorited ? t('operations.favFail') : t('operations.unfavFail'))
      }
    },
    [refreshCallback, clearSelectionCallback, t]
  )

  /**
   * 预览文件（本窗口全屏弹窗，fileList 用于同类型文件切换）
   */
  const openPreview = useCallback((file: FileItem, fileList: FileItem[]) => {
    const index = fileList.findIndex((f) => f.id === file.id)
    usePreviewStore.getState().openPreview(fileList, Math.max(index, 0))
  }, [])

  /**
   * 打开详细信息弹窗
   */
  const openDetail = useCallback((file: FileItem) => {
    setDetailFile(file)
    setDetailModalVisible(true)
  }, [])

  return {
    // 模态框状态
    createFolderModalVisible,
    setCreateFolderModalVisible,
    createTextModalVisible,
    setCreateTextModalVisible,
    createTextSuffix,
    renameModalVisible,
    setRenameModalVisible,
    moveModalVisible,
    setMoveModalVisible,
    shareModalVisible,
    setShareModalVisible,
    deleteDialogVisible,
    setDeleteDialogVisible,
    permanentDeleteDialogVisible,
    setPermanentDeleteDialogVisible,
    detailModalVisible,
    setDetailModalVisible,

    // 操作的文件
    renamingFile,
    movingFile,
    movingFiles,
    sharingFile,
    sharingFiles,
    deletingFiles,
    permanentlyDeletingFiles,
    detailFile,

    // 操作方法
    openCreateFolderModal,
    handleCreateFolder,
    openCreateTextModal,
    handleCreateText,
    openTextEditor,
    openRenameModal,
    handleRename,
    openMoveModal,
    openBatchMoveModal,
    handleMove,
    openShareModal,
    openBatchShareModal,
    copyDirectLink,
    openDeleteConfirm,
    openBatchDeleteConfirm,
    handleDelete,
    handlePermanentDelete,
    handleDownload,
    handleFavorite,
    openPreview,
    openDetail,
  }
}
