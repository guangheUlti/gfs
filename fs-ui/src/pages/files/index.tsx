import { useState, useEffect, useRef, useMemo } from 'react'
import type { FileItem } from '@/types/file'
import {
  List,
  LayoutGrid,
  FileText,
  FilePlus,
  Upload,
  FolderPlus,
  RefreshCw,
  Search,
  X,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import {
  createBatchDownloadTask,
  getFolderDownloadTask,
  downloadFolderDownloadZip,
} from '@/api/transfer'
import { usePermission } from '@/hooks/use-permission'
import { Button } from '@/components/ui/button'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { NoPermission } from '@/components/no-permission'
import {
  Toolbar,
  FileBreadcrumb,
  StorageSwitcher,
  FileGridView,
  FileListView,
  CreateFolderModal,
  CreateTextModal,
  RenameModal,
  MoveModal,
  ShareModal,
  FileBulkSelectionBar,
  RecycleBinView,
  DeleteConfirmDialog,
  PermanentDeleteConfirmDialog,
  FileDetailModal,
  MySharesView,
} from './components'
import UploadModal from './components/UploadModal'
import UploadPanel from './components/UploadPanel'
import { moveFiles } from '@/api/file'
import { useFileClipboardStore } from '@/store/file-clipboard'
import { useExternalFileDrop } from './hooks/useExternalFileDrop'
import { useFileList } from './hooks/useFileList'
import { useFileOperations } from './hooks/useFileOperations'
import { useFileUndo } from './hooks/useFileUndo'
import { useMarqueeSelection } from './hooks/useMarqueeSelection'

type ViewMode = 'list' | 'grid'

const ARROW_KEYS = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'])

/** 网格视图一行卡片数：按首行元素数推算，响应式列数变化时自动适配 */
function getGridColumnCount(container: HTMLElement): number {
  const nodes = Array.from(
    container.querySelectorAll<HTMLElement>('[data-file-id]')
  )
  if (nodes.length === 0) return 1
  const firstTop = nodes[0].getBoundingClientRect().top
  let columns = 0
  for (const node of nodes) {
    if (Math.abs(node.getBoundingClientRect().top - firstTop) < 2) {
      columns += 1
    } else {
      break
    }
  }
  return Math.max(1, columns)
}

export default function FilesPage() {
  const { t } = useTranslation('files')
  const { t: tc } = useTranslation('common')
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { hasPermission } = usePermission()

  // 视图模式：默认列表
  const [viewMode, setViewMode] = useState<ViewMode>(
    (searchParams.get('viewMode') as ViewMode) || 'list'
  )

  // 选中的文件
  const [selectedKeys, setSelectedKeys] = useState<string[]>([])

  // 触屏多选模式：进入后点文件即切换选中，退出时保留已选（方便接着批量操作）
  const [selectMode, setSelectMode] = useState(false)

  // 上传弹窗状态
  const [uploadModalOpen, setUploadModalOpen] = useState(false)

  // 拖拽状态
  const [dragTargetName, setDragTargetName] = useState<string | null>(null)
  const [draggedCount, setDraggedCount] = useState(0)
  const [dragMode, setDragMode] = useState<'move' | 'copy' | 'upload' | null>(
    null
  )

  // 键盘导航：当前活动行与 Shift 范围锚点；粘贴请求防连点
  const navIndexRef = useRef(-1)
  const navAnchorRef = useRef<number | null>(null)
  const pastePendingRef = useRef(false)

  const fileScrollAreaRef = useRef<HTMLDivElement>(null)

  // 批量 zip 打包下载中，用于防止重复触发与组件卸载后的清理
  const batchDownloadRef = useRef<{
    active: boolean
    onCancel: (() => void) | null
  }>({ active: false, onCancel: null })

  const fileList = useFileList()
  // 解构出稳定引用：避免每次渲染都重建键盘监听的依赖
  const {
    recordUndo,
    undo: undoAction,
    redo: redoAction,
  } = useFileUndo()

  /**
   * 清空选中
   */
  const clearSelection = () => {
    setSelectedKeys([])
    navIndexRef.current = -1
    navAnchorRef.current = null
  }

  // 鼠标框选：仅从列表空白处起拖，拖出的矩形实时决定选中项
  const { marqueeRect, onMarqueePointerDown, shouldSuppressClick } =
    useMarqueeSelection({
      containerRef: fileScrollAreaRef,
      enabled: !fileList.loading && fileList.fileList.length > 0,
      onChange: (keys) => {
        setSelectedKeys(keys)
        // 框选后方向键从最早选中的一项继续走
        navIndexRef.current =
          keys.length > 0
            ? fileList.fileList.findIndex((file) => file.id === keys[0])
            : -1
        navAnchorRef.current = null
      },
    })

  const operations = useFileOperations(
    // 操作后的列表校准走静默刷新：不进入 loading 态、不重建整表，滚动位置不跳回顶部
    fileList.refreshSilent,
    clearSelection,
    () => {
      // 在特殊视图中创建文件夹后，返回全部文件页面
      if (isFavoritesView || isRecentsView || isTypeFilter || isDirFilter) {
        navigate(`/files?viewMode=${viewMode}`)
      }
    },
    fileList.updateFileItems,
    fileList.removeFileItems,
    {
      getFileById: (id) => fileList.fileList.find((file) => file.id === id),
      recordUndo,
    }
  )

  // 计算当前视图类型
  const viewType = searchParams.get('view')
  const fileType = searchParams.get('type')
  const isDirFilter = searchParams.get('isDir') === 'true'
  const isFavoritesView = viewType === 'favorites'
  const isRecentsView = viewType === 'recents'
  const isRecycleBin = viewType === 'recycle'
  const isSharesView = viewType === 'shares'
  const isTypeFilter = !!fileType
  // 全部文件视图：上传/新建文件夹这类目录级操作只在这里有意义，筛选/收藏/历史视图不提供
  const isAllFilesView =
    !isFavoritesView && !isRecentsView && !isTypeFilter && !isDirFilter
  const canRead = hasPermission('file:read')
  const canWrite = hasPermission('file:write')
  const canShare = hasPermission('file:share')

  /** 粘贴目标只认「全部文件」里的具体目录，收藏/搜索/回收站等视图不提供粘贴 */
  const canPaste =
    canWrite &&
    isAllFilesView &&
    !isRecycleBin &&
    !isSharesView &&
    !fileList.searchKeyword

  // 操作系统文件/文件夹拖入上传：仅全部文件视图且有写权限时启用
  const externalDrop = useExternalFileDrop({
    enabled: canWrite && isAllFilesView,
    parentId: fileList.currentParentId,
  })

  const specialViewTitle = useMemo(() => {
    if (isFavoritesView) return t('index.viewFavorites')
    if (isRecentsView) return t('index.viewRecents')
    if (isDirFilter) return t('index.viewFolder')
    if (fileType === 'document') return t('index.viewDocument')
    if (fileType === 'image') return t('index.viewImage')
    if (fileType === 'video') return t('index.viewVideo')
    if (fileType === 'audio') return t('index.viewAudio')
    if (fileType === 'other') return t('index.viewOther')
    return undefined
  }, [isFavoritesView, isRecentsView, isDirFilter, fileType, t])

  // 判断文件夹
  const selectedFiles = fileList.fileList.filter((file) =>
    selectedKeys.includes(file.id)
  )
  const hasUnfavorited = selectedFiles.some((f) => !f.isFavorite)

  /**
   * 键盘快捷键
   */
  useEffect(() => {
    // 输入态（搜索框/改名框等）不响应文件操作快捷键：
    // Delete 在输入框里是正常编辑字符，绝不能误弹删除确认框
    const isInteractiveTarget = () => {
      const el = document.activeElement as HTMLElement | null
      if (!el) return false
      // 弹窗已关闭但节点仍在：焦点可能残留在隐藏输入框里，不能算作输入态
      if (el.closest('[data-state="closed"]')) return false
      const tag = el.tagName
      return (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        el.isContentEditable
      )
    }

    // Radix 关闭后仍保留 role="dialog" 节点（data-state="closed"），必须只认真正打开的弹窗，
    // 否则任何弹窗关闭过一次后所有快捷键都会失效
    const isDialogOpen = () =>
      !!document.querySelector(
        '[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"]'
      )

    const handleKeyDown = (e: KeyboardEvent) => {
      // ESC 键退出多选模式并取消选中
      if (e.key === 'Escape') {
        if (selectedKeys.length > 0) clearSelection()
        if (selectMode) setSelectMode(false)
      }

      // F5 刷新文件列表而非重载整个页面：F5 无破坏性且重载会丢失
      // 搜索/筛选等页面状态，故不限制输入态/弹窗态，一律拦截
      if (e.key === 'F5') {
        e.preventDefault()
        fileList.refresh()
        return
      }

      // Ctrl/Cmd 组合键：剪贴板、全选、撤销重做；输入框和弹窗内让位给原生行为
      if ((e.ctrlKey || e.metaKey) && !isInteractiveTarget() && !isDialogOpen()) {
        const key = e.key.toLowerCase()
        if (key === 'a') {
          e.preventDefault()
          if (fileList.fileList.length > 0) handleSelectAll(true)
          return
        }
        if (key === 'c') {
          e.preventDefault()
          handleCopySelection()
          return
        }
        if (key === 'x') {
          e.preventDefault()
          handleCutSelection()
          return
        }
        if (key === 'v') {
          e.preventDefault()
          if (canPaste) {
            void handlePaste()
          } else if (useFileClipboardStore.getState().mode) {
            // 收藏/搜索/回收站等视图没有明确的粘贴目标
            toast.error(t('operations.pasteUnavailable'))
          }
          return
        }
        if (key === 'z') {
          e.preventDefault()
          if (e.shiftKey) {
            void redoAction()
          } else {
            void undoAction()
          }
          return
        }
        if (key === 'y') {
          e.preventDefault()
          void redoAction()
          return
        }
      }

      // 方向键移动选中：列表上下移动，网格按整行上下、按单个左右移动
      if (
        ARROW_KEYS.has(e.key) &&
        !isInteractiveTarget() &&
        !isDialogOpen() &&
        !isRecycleBin &&
        !isSharesView
      ) {
        const items = fileList.fileList
        if (items.length === 0) return
        e.preventDefault()

        const vertical = e.key === 'ArrowUp' || e.key === 'ArrowDown'
        const prevIndex = navIndexRef.current
        let index = prevIndex
        if (index < 0 || index >= items.length) {
          // 未选中时：向下选第一个，向上选最后一个
          index =
            e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? items.length - 1 : 0
        } else {
          let step = 1
          if (viewMode === 'grid' && vertical) {
            step = fileScrollAreaRef.current
              ? getGridColumnCount(fileScrollAreaRef.current)
              : 1
          } else if (viewMode !== 'grid' && !vertical) {
            // 列表视图的左右键不用于切换选中
            return
          }
          const delta =
            e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -step : step
          index = Math.min(items.length - 1, Math.max(0, index + delta))
        }

        const item = items[index]
        if (!item) return
        if (e.shiftKey) {
          const prevAnchor = navAnchorRef.current
          // 首次 Shift+方向键：锚点取移动前的位置，才能把「当前项到新位置」连成区间
          const anchor =
            prevAnchor !== null && prevAnchor >= 0
              ? prevAnchor
              : prevIndex >= 0 && prevIndex < items.length
                ? prevIndex
                : index
          const from = Math.min(anchor, index)
          const to = Math.max(anchor, index)
          setSelectedKeys(items.slice(from, to + 1).map((f) => f.id))
          navAnchorRef.current = anchor
        } else {
          setSelectedKeys([item.id])
          navAnchorRef.current = null
        }
        navIndexRef.current = index
        requestAnimationFrame(() => {
          const nodes = fileScrollAreaRef.current?.querySelectorAll<HTMLElement>(
            '[data-file-id]'
          )
          nodes?.[index]?.scrollIntoView({ block: 'nearest' })
        })
        return
      }

      // F2 / Delete：输入框内或弹窗打开时让位给组件自身交互
      // （删除/永久删除确认框是 alertdialog 角色，需一并检测）
      if (e.key === 'F2' || e.key === 'Delete') {
        if (isInteractiveTarget()) return
        if (isDialogOpen()) return
      }

      // F2 键重命名（仅当选中单个文件时）
      if (canWrite && e.key === 'F2' && selectedKeys.length === 1) {
        e.preventDefault()
        const selectedFile = fileList.fileList.find(
          (file) => file.id === selectedKeys[0]
        )
        if (selectedFile) {
          operations.openRenameModal(selectedFile)
        }
      }

      // Delete 键触发删除确认：单选/多选复用右键菜单同一入口，
      // 回收站关闭时 open 内部自动切换为「永久删除」确认框
      if (canWrite && e.key === 'Delete' && selectedKeys.length >= 1) {
        e.preventDefault()
        if (selectedKeys.length === 1) {
          const selectedFile = fileList.fileList.find(
            (file) => file.id === selectedKeys[0]
          )
          if (selectedFile) {
            operations.openDeleteConfirm(selectedFile)
            return
          }
        }
        operations.openBatchDeleteConfirm(selectedFiles)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [
    selectedKeys,
    selectedFiles,
    fileList.fileList,
    fileList.currentParentId,
    canWrite,
    canPaste,
    selectMode,
    viewMode,
    isRecycleBin,
    isSharesView,
    undoAction,
    redoAction,
    t,
  ])

  /**
   * 当目录变化时清空选中状态并退出多选模式
   */
  useEffect(() => {
    clearSelection()
    setSelectMode(false)
    navIndexRef.current = -1
    navAnchorRef.current = null
  }, [fileList.currentParentId, viewType, fileType, isDirFilter])

  /**
   * 监听文件上传完成事件
   */
  useEffect(() => {
    const handleUploadComplete = (event: Event) => {
      const customEvent = event as CustomEvent<{ parentId?: string }>
      const { parentId } = customEvent.detail

      // 如果是当前目录的文件，静默刷新列表（不闪白、不跳顶）
      if (parentId === fileList.currentParentId) {
        fileList.refreshSilent()
      }
    }

    window.addEventListener('file-upload-complete', handleUploadComplete)

    return () => {
      window.removeEventListener('file-upload-complete', handleUploadComplete)
    }
  }, [fileList.currentParentId, fileList.refreshSilent])

  /**
   * 打开上传弹窗
   */
  const handleOpenUploadModal = () => {
    if (!canWrite) return
    setUploadModalOpen(true)
  }

  /**
   * 搜索按钮/回车：有关键词就按关键词搜；
   * 搜索框为空时当刷新用（原 commitSearch('') 在无生效搜索时不改 URL，点了等于没反应）；
   * 有生效中的搜索但输入被清空时，仍然走清空恢复全量。
   */
  const handleSearch = (keyword: string) => {
    const kw = keyword.trim()
    if (kw) {
      fileList.commitSearch(kw)
      return
    }
    if (fileList.searchKeyword) {
      fileList.commitSearch('')
      return
    }
    fileList.refresh()
  }

  /**
   * 切换当前存储：清空搜索与选中，回到新存储的根目录；URL 无变化（本就在根目录）时手动刷新
   */
  const handleStorageSwitch = () => {
    clearSelection()
    setSelectMode(false)
    fileList.setSearchInput('')
    const params = new URLSearchParams(searchParams)
    const hadScopedParams = params.has('keyword') || params.has('parentId')
    params.delete('keyword')
    params.delete('parentId')
    navigate(`/files?${params.toString()}`)
    if (!hadScopedParams) {
      fileList.refresh()
    }
  }

  /**
   * 处理文件点击
   */
  const handleFileClick = (file: FileItem) => {
    if (file.isDir) {
      // 进入文件夹时清空选中状态
      clearSelection()

      // 如果是在特殊视图中，进入文件夹后清除筛选参数，回到全部文件
      if (isFavoritesView || isRecentsView || isTypeFilter || isDirFilter) {
        // 使用 navigate 跳转到全部文件视图
        navigate(`/files?parentId=${file.id}&viewMode=${viewMode}`)
      } else {
        fileList.enterFolder(file.id, viewMode)
      }
    }
  }

  /**
   * 全选/取消全选
   */
  const handleSelectAll = (checked: boolean) => {
    if (checked) {
      setSelectedKeys(fileList.fileList.map((f) => f.id))
    } else {
      setSelectedKeys([])
    }
  }

  /**
   * 复制到页内剪贴板：只记住对象，不立刻复制，等 Ctrl+V 时再落到目标目录
   */
  const handleCopySelection = () => {
    if (selectedFiles.length === 0) return
    useFileClipboardStore.getState().setClipboard('copy', selectedFiles)
    toast.success(
      t('operations.copyToClipboardOk', { count: selectedFiles.length })
    )
  }

  /**
   * 剪切到页内剪贴板：粘贴成功后才从原目录移走
   */
  const handleCutSelection = () => {
    if (!canWrite || selectedFiles.length === 0) return
    useFileClipboardStore.getState().setClipboard('cut', selectedFiles)
    toast.success(
      t('operations.cutToClipboardOk', { count: selectedFiles.length })
    )
  }

  /**
   * 粘贴到当前目录：复制模式经复制接口，剪切模式为移动；两者都可撤销
   */
  const handlePaste = async () => {
    if (!canPaste || pastePendingRef.current) return
    const clipboard = useFileClipboardStore.getState()
    if (!clipboard.mode || clipboard.items.length === 0) return

    const targetDirId = fileList.currentParentId ?? ''
    const fileIds = clipboard.items.map((item) => item.id)
    pastePendingRef.current = true
    try {
      if (clipboard.mode === 'copy') {
        await operations.handleCopy(fileIds, targetDirId)
        return
      }

      // 已经在目标目录里的项无需移动（资源管理器同样跳过），全部在内则直接丢弃剪贴板
      const movingItems = clipboard.items.filter(
        (item) => (item.parentId ?? '') !== targetDirId
      )
      if (movingItems.length === 0) {
        useFileClipboardStore.getState().clear()
        return
      }
      const movingIds = movingItems.map((item) => item.id)

      await moveFiles(targetDirId, movingIds)
      toast.success(
        t('operations.pasteMoveOk', { count: movingItems.length })
      )

      const groups = new Map<string, string[]>()
      for (const item of movingItems) {
        const originalDir = item.parentId ?? ''
        const ids = groups.get(originalDir) ?? []
        ids.push(item.id)
        groups.set(originalDir, ids)
      }
      recordUndo({
        label: t('undo.labelMove'),
        undo: async () => {
          for (const [dirId, ids] of groups) {
            await moveFiles(dirId, ids)
          }
          fileList.refreshSilent()
        },
        redo: async () => {
          await moveFiles(targetDirId, movingIds)
          fileList.refreshSilent()
        },
      })

      useFileClipboardStore.getState().clear()
      clearSelection()
      fileList.refreshSilent()
    } catch (error) {
      // 请求拦截器已提示错误；剪切失败时保留剪贴板，便于重试
    } finally {
      pastePendingRef.current = false
    }
  }

  /**
   * 批量操作
   */
  const handleBatchShare = () => {
    if (selectedFiles.length === 0) return
    operations.openBatchShareModal(selectedFiles)
  }

  const handleBatchFavorite = async () => {
    if (!canWrite) return
    if (selectedFiles.length === 0) return
    await operations.handleFavorite(selectedFiles)
  }

  const handleBatchMove = () => {
    if (!canWrite) return
    if (selectedFiles.length === 0) return
    operations.openBatchMoveModal(selectedFiles)
  }

  const handleBatchDelete = () => {
    if (selectedFiles.length === 0) return
    operations.openBatchDeleteConfirm(selectedFiles)
  }

  /**
   * 批量 zip 打包下载：创建任务后轮询进度，完成后触发下载
   */
  const handleBatchDownload = async () => {
    if (selectedFiles.length === 0) return
    if (batchDownloadRef.current.active) return

    const ids = selectedFiles.map((f) => f.id)
    let stopped = false
    const stopPolling = () => {
      stopped = true
      batchDownloadRef.current.active = false
      batchDownloadRef.current.onCancel = null
    }

    try {
      const task = await createBatchDownloadTask(ids)
      if (!task?.taskId) {
        toast.error(t('bulk.downloadError'))
        return
      }
      batchDownloadRef.current.active = true
      batchDownloadRef.current.onCancel = stopPolling
      toast.info(t('bulk.downloadPrompt'))

      const poll = async () => {
        if (stopped) return
        try {
          const current = await getFolderDownloadTask(task.taskId)
          if (stopped) return
          if (current.status === 'completed') {
            stopPolling()
            await downloadFolderDownloadZip(task.taskId)
            toast.success(t('bulk.downloadReady'))
            return
          }
          if (current.status === 'failed' || current.status === 'canceled') {
            stopPolling()
            toast.error(current.errorMessage || t('bulk.downloadError'))
            return
          }
          // queued / scaning / scanning / packing 时继续轮询
          setTimeout(poll, 1000)
        } catch {
          stopPolling()
          toast.error(t('bulk.downloadError'))
        }
      }
      poll()
    } catch {
      stopPolling()
      toast.error(t('bulk.downloadError'))
    }
  }

  /**
   * 组件卸载时停止批量打包轮询
   */
  useEffect(() => {
    return () => {
      batchDownloadRef.current.onCancel?.()
    }
  }, [])

  /**
   * 拖拽移动文件
   */
  const handleMoveFiles = async (fileIds: string[], targetDirId: string) => {
    if (!canWrite) return
    await operations.handleMove(fileIds, targetDirId)
  }

  /**
   * 处理拖拽状态变化
   */
  const handleDragStateChange = (
    dropTargetName: string | null,
    draggedCount: number,
    mode: 'move' | 'copy' | 'upload' | null
  ) => {
    setDragTargetName(dropTargetName)
    setDraggedCount(draggedCount)
    setDragMode(mode)
  }

  const isAllSelected =
    fileList.fileList.length > 0 &&
    selectedKeys.length === fileList.fileList.length

  // 如果是回收站或我的分享视图,显示对应的特殊组件
  if (isRecycleBin) {
    if (!canWrite) return <NoPermission />
    return <RecycleBinView />
  }

  if (isSharesView) {
    if (!canShare) return <NoPermission />
    return <MySharesView />
  }

  if (!canRead) {
    return <NoPermission />
  }

  return (
    <div className='flex h-full flex-col'>
      {/* 顶部工具栏：窄屏时标题与工具栏各占一行；顶部留白大、底部留白小，分隔线贴近内容 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 pt-4 pb-3 sm:px-6 sm:pt-6 sm:pb-4'>
        {/* 面包屑导航；搜索态改为提示条 */}
        <div className='w-full min-w-0 sm:w-auto sm:flex-1'>
          {fileList.searchKeyword ? (
            <div className='flex min-w-0 items-center gap-1.5 text-sm text-muted-foreground'>
              <Search className='h-4 w-4 shrink-0' />
              <span className='min-w-0 truncate'>
                {t('index.searchBanner', {
                  keyword: fileList.searchKeyword,
                })}
              </span>
              <Button
                variant='ghost'
                size='sm'
                className='h-6 shrink-0 gap-1 px-1.5 text-xs'
                onClick={() => fileList.commitSearch('')}
              >
                <X className='h-3.5 w-3.5' />
                {t('index.clearSearch')}
              </Button>
            </div>
          ) : (
            <FileBreadcrumb
              breadcrumbPath={fileList.breadcrumbPath}
              customTitle={
                fileList.breadcrumbPath.length === 0 &&
                (isFavoritesView || isRecentsView || isTypeFilter || isDirFilter)
                  ? specialViewTitle
                  : undefined
              }
              onNavigate={fileList.navigateToFolder}
            />
          )}
        </div>

        {/* 存储切换器：多存储激活时在根面包屑旁切换当前存储（仅全部文件视图） */}
        {isAllFilesView && (
          <StorageSwitcher onChanged={handleStorageSwitch} />
        )}

        {/* 右侧工具栏 */}
        <Toolbar
          searchKeyword={fileList.searchInput}
          onSearchChange={fileList.setSearchInput}
          onSearch={handleSearch}
          onUpload={handleOpenUploadModal}
          onCreateFolder={operations.openCreateFolderModal}
          onCreateText={operations.openCreateTextModal}
          hideActions={!isAllFilesView}
          selectMode={selectMode}
          onToggleSelectMode={
            fileList.fileList.length > 0
              ? () => setSelectMode((v) => !v)
              : undefined
          }
        />
      </div>

      {/* 固定行：全选/计数与视图切换，紧贴列表上方（类资源管理器），不随列表滚动 */}
      <div className='flex shrink-0 flex-wrap items-center justify-between gap-x-3 gap-y-2 px-3 pt-2 pb-1.5 sm:px-6 sm:pt-2.5'>
        <div className='flex items-center gap-2'>
          {/* 用文字按钮而非复选框：选中态已由行/卡片背景色表达 */}
          {fileList.fileList.length > 0 && (
            <Button
              variant='ghost'
              size='sm'
              className='text-muted-foreground hover:text-foreground'
              onClick={() => handleSelectAll(!isAllSelected)}
            >
              {isAllSelected ? t('index.deselectAll') : t('index.selectAll')}
            </Button>
          )}
          <span className='text-sm text-muted-foreground'>
            {selectedKeys.length > 0
              ? t('index.selectedCount', { count: selectedKeys.length })
              : t('index.totalCount', { total: fileList.total })}
          </span>
        </div>
        <ToggleGroup
          type='single'
          value={viewMode}
          onValueChange={(value) => value && setViewMode(value as ViewMode)}
        >
          <ToggleGroupItem
            value='list'
            aria-label={t('index.ariaList')}
            size='sm'
          >
            <List className='h-4 w-4' />
          </ToggleGroupItem>
          <ToggleGroupItem
            value='grid'
            aria-label={t('index.ariaGrid')}
            size='sm'
          >
            <LayoutGrid className='h-4 w-4' />
          </ToggleGroupItem>
        </ToggleGroup>
      </div>

      {/* 主内容区域：滚动只发生在这一层的文件列表上，顶部留白放在滚动容器外，表头才能一上来就贴住固定区 */}
      <div className='relative flex-1 overflow-hidden pt-1 sm:pt-1.5'>
        <ContextMenu>
          <ContextMenuTrigger asChild>
            <div
              ref={fileScrollAreaRef}
              className='h-full overflow-x-hidden overflow-y-auto px-3 pb-3 sm:px-6 sm:pb-6'
              onPointerDown={onMarqueePointerDown}
              onDragEnter={externalDrop.handleDragEnter}
              onDragOver={externalDrop.handleDragOver}
              onDragLeave={externalDrop.handleDragLeave}
              onDrop={externalDrop.handleDrop}
              onClick={(e) => {
                // 框选刚松手时浏览器会补发一次 click，别让它把选中清掉
                if (shouldSuppressClick()) return
                const tgt = e.target as HTMLElement
                if (tgt.closest('[data-file-id]')) return
                if (tgt.closest('thead')) return
                if (tgt.closest('button')) return
                clearSelection()
              }}
            >
              {fileList.loading ? (
                <div className='flex h-full items-center justify-center'>
                  <p className='text-muted-foreground'>{tc('loading')}</p>
                </div>
              ) : fileList.fileList.length === 0 ? (
                fileList.searchKeyword ? (
                  <div className='flex h-full items-center justify-center'>
                    <Empty className='border-none'>
                      <EmptyHeader>
                        <EmptyMedia variant='icon'>
                          <Search className='h-12 w-12' />
                        </EmptyMedia>
                        <EmptyTitle>
                          {t('index.searchEmptyTitle', {
                            keyword: fileList.searchKeyword,
                          })}
                        </EmptyTitle>
                        <EmptyDescription>
                          {t('index.searchEmptyDesc')}
                        </EmptyDescription>
                      </EmptyHeader>
                      <EmptyContent>
                        <Button
                          size='sm'
                          variant='outline'
                          onClick={() => fileList.commitSearch('')}
                        >
                          <X className='mr-2 h-4 w-4' />
                          {t('index.clearSearch')}
                        </Button>
                      </EmptyContent>
                    </Empty>
                  </div>
                ) : (
              <>
                <div className='flex h-full items-center justify-center'>
                  <Empty className='border-none'>
                    <EmptyHeader>
                      <EmptyMedia variant='icon'>
                        <FileText className='h-12 w-12' />
                      </EmptyMedia>
                      <EmptyTitle>{t('index.emptyTitle')}</EmptyTitle>
                      <EmptyDescription>
                        {t('index.emptyDesc')}
                      </EmptyDescription>
                    </EmptyHeader>
                    {!fileList.searchKeyword && isAllFilesView && (
                      <EmptyContent>
                        <div className='flex gap-2'>
                          {canWrite && (
                            <Button size='sm' onClick={handleOpenUploadModal}>
                              <Upload className='mr-2 h-4 w-4' />
                              {t('index.uploadFile')}
                            </Button>
                          )}
                          {canWrite && (
                            <Button
                              variant='outline'
                              size='sm'
                              onClick={operations.openCreateFolderModal}
                            >
                              <FolderPlus className='mr-2 h-4 w-4' />
                              {t('index.newFolder')}
                            </Button>
                          )}
                        </div>
                      </EmptyContent>
                    )}
                  </Empty>
                </div>
              </>
                )
              ) : (
                /* 不撑满高度：内容不足时强撑到 100% 会因亚像素取整虚报溢出，白得一个滚动条 */
                <div className='min-h-0'>
                  {viewMode === 'grid' ? (
                    <FileGridView
                      fileList={fileList.fileList}
                      searchKeyword={fileList.searchKeyword}
                      selectedKeys={selectedKeys}
                      onSelectionChange={setSelectedKeys}
                      onFileClick={handleFileClick}
                      onDownload={operations.handleDownload}
                      onShare={operations.openShareModal}
                      onDelete={operations.openDeleteConfirm}
                      onRename={operations.openRenameModal}
                      onMove={operations.openMoveModal}
                      onMoveFiles={handleMoveFiles}
                      onCopyFiles={operations.handleCopy}
                      onExternalFilesToFolder={(files, folder) =>
                        externalDrop.uploadFilesTo(files, folder.id)
                      }
                      onActiveIndexChange={(index) => {
                        navIndexRef.current = index
                        navAnchorRef.current = null
                      }}
                      onFavorite={operations.handleFavorite}
                      onPreview={(file) =>
                        operations.openPreview(file, fileList.fileList)
                      }
                      onEdit={operations.openTextEditor}
                      onDetail={operations.openDetail}
                      onDragStateChange={handleDragStateChange}
                      onBatchShare={handleBatchShare}
                      onBatchMove={handleBatchMove}
                      onBatchDelete={handleBatchDelete}
                      selectMode={selectMode}
                      hasMore={fileList.hasMore}
                      loadingMore={fileList.loadingMore}
                      onLoadMore={fileList.loadMore}
                      scrollRootRef={fileScrollAreaRef}
                    />
                  ) : (
                    <FileListView
                      fileList={fileList.fileList}
                      searchKeyword={fileList.searchKeyword}
                      selectedKeys={selectedKeys}
                      onSelectionChange={setSelectedKeys}
                      onFileClick={handleFileClick}
                      onSortChange={fileList.handleSortChange}
                      orderBy={fileList.orderBy}
                      orderDirection={fileList.orderDirection}
                      /* 最近使用视图由后端固定按访问时间倒序，表头排序点了也没用，直接关掉 */
                      sortable={!isRecentsView}
                      onDownload={operations.handleDownload}
                      onShare={operations.openShareModal}
                      onDelete={operations.openDeleteConfirm}
                      onRename={operations.openRenameModal}
                      onMove={operations.openMoveModal}
                      onMoveFiles={handleMoveFiles}
                      onCopyFiles={operations.handleCopy}
                      onExternalFilesToFolder={(files, folder) =>
                        externalDrop.uploadFilesTo(files, folder.id)
                      }
                      onActiveIndexChange={(index) => {
                        navIndexRef.current = index
                        navAnchorRef.current = null
                      }}
                      onFavorite={operations.handleFavorite}
                      onPreview={(file) =>
                        operations.openPreview(file, fileList.fileList)
                      }
                      onEdit={operations.openTextEditor}
                      onDetail={operations.openDetail}
                      onDragStateChange={handleDragStateChange}
                      onBatchShare={handleBatchShare}
                      onBatchMove={handleBatchMove}
                      onBatchDelete={handleBatchDelete}
                      selectMode={selectMode}
                      hasMore={fileList.hasMore}
                      loadingMore={fileList.loadingMore}
                      onLoadMore={fileList.loadMore}
                      scrollRootRef={fileScrollAreaRef}
                    />
                  )}
                </div>
              )}
            </div>
          </ContextMenuTrigger>
          <ContextMenuContent>
            {canWrite && (
              <ContextMenuItem onClick={operations.openCreateFolderModal}>
                <FolderPlus className='mr-2 h-4 w-4' />
                {t('index.newFolder')}
              </ContextMenuItem>
            )}
            {canWrite && (
              <ContextMenuItem
                onClick={() => operations.openCreateTextModal('txt')}
              >
                <FilePlus className='mr-2 h-4 w-4' />
                {t('index.newTextFile')}
              </ContextMenuItem>
            )}
            {canWrite && (
              <ContextMenuItem onClick={handleOpenUploadModal}>
                <Upload className='mr-2 h-4 w-4' />
                {t('index.uploadFile')}
              </ContextMenuItem>
            )}
            {canWrite && <ContextMenuSeparator />}
            <ContextMenuItem onClick={() => fileList.refresh()}>
              <RefreshCw className='mr-2 h-4 w-4' />
              {t('index.refresh')}
            </ContextMenuItem>
          </ContextMenuContent>
        </ContextMenu>

        {/* 系统文件拖入提示：drop 才真正入队，此层只做视觉且不拦截指针 */}
        {/* 拖到文件夹行时由行高亮指明目标，不再盖整块上传遮罩 */}
        {externalDrop.isDragging && dragMode !== 'upload' && (
          <div className='pointer-events-none absolute inset-0 z-30 flex items-center justify-center rounded-xl border-2 border-dashed border-primary bg-primary/10'>
            <div className='flex flex-col items-center gap-2 rounded-lg bg-background/85 px-6 py-4 backdrop-blur-sm'>
              <Upload className='h-8 w-8 text-primary' />
              <span className='text-sm font-medium'>
                {t('upload.dropZoneHint')}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* 拖入文件与目录内已有文件重名时，先问「替换 / 保留两者」 */}
      {externalDrop.conflictDialog}

      {/* 拖拽移动提示：fixed 底部，避免插入文档流导致布局抖动 */}
      {dragTargetName && (
        <div
          className={cn(
            'pointer-events-none fixed left-1/2 z-[90] -translate-x-1/2 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 shadow-md dark:border-blue-900 dark:bg-blue-950/40',
            selectedKeys.length > 1 ? 'bottom-28' : 'bottom-16'
          )}
          role='status'
          aria-live='polite'
        >
          <div className='flex items-center gap-2 text-sm text-blue-800 dark:text-blue-300'>
            <svg
              className='h-4 w-4 shrink-0'
              fill='none'
              stroke='currentColor'
              viewBox='0 0 24 24'
            >
              <path
                strokeLinecap='round'
                strokeLinejoin='round'
                strokeWidth={2}
                d='M13 7l5 5m0 0l-5 5m5-5H6'
              />
            </svg>
            <span>
              {dragMode === 'copy'
                ? t('index.dragCopyTo', { name: dragTargetName })
                : dragMode === 'upload'
                  ? t('index.dragUploadTo', { name: dragTargetName })
                  : t('index.dragMoveTo', { name: dragTargetName })}{' '}
              {dragMode !== 'upload' &&
                draggedCount > 1 &&
                t('index.dragMoveToMulti', { count: draggedCount })}
            </span>
          </div>
        </div>
      )}

      {/* 框选矩形：视口坐标 + fixed，与命中测试用的 getBoundingClientRect 同源 */}
      {marqueeRect && (
        <div
          className='pointer-events-none fixed z-[80] rounded-sm border border-primary/40 bg-primary/10'
          style={{
            top: marqueeRect.top,
            left: marqueeRect.left,
            width: marqueeRect.width,
            height: marqueeRect.height,
          }}
        />
      )}

      <FileBulkSelectionBar
        selectedCount={selectedKeys.length}
        hasUnfavorited={hasUnfavorited}
        onShare={handleBatchShare}
        onFavorite={handleBatchFavorite}
        onMove={handleBatchMove}
        onDelete={handleBatchDelete}
        onDownload={handleBatchDownload}
        onClear={clearSelection}
      />

      {/* 上传弹窗 */}
      <UploadModal
        open={uploadModalOpen}
        onOpenChange={setUploadModalOpen}
        parentId={fileList.currentParentId}
      />

      {/* 上传进度面板 */}
      <UploadPanel onSuccess={fileList.refresh} />

      {/* 模态框 */}
      <CreateFolderModal
        open={operations.createFolderModalVisible}
        onOpenChange={operations.setCreateFolderModalVisible}
        parentId={fileList.currentParentId}
        onConfirm={operations.handleCreateFolder}
      />

      <CreateTextModal
        open={operations.createTextModalVisible}
        onOpenChange={operations.setCreateTextModalVisible}
        suffix={operations.createTextSuffix}
        parentId={fileList.currentParentId}
        onConfirm={operations.handleCreateText}
      />

      <RenameModal
        open={operations.renameModalVisible}
        onOpenChange={operations.setRenameModalVisible}
        file={operations.renamingFile}
        onConfirm={operations.handleRename}
      />

      <MoveModal
        open={operations.moveModalVisible}
        onOpenChange={operations.setMoveModalVisible}
        file={operations.movingFile}
        files={operations.movingFiles}
        onConfirm={operations.handleMove}
        onRefresh={fileList.refresh}
      />

      <ShareModal
        open={operations.shareModalVisible}
        onOpenChange={operations.setShareModalVisible}
        file={operations.sharingFile}
        files={operations.sharingFiles}
        onSuccess={clearSelection}
      />

      <DeleteConfirmDialog
        open={operations.deleteDialogVisible}
        onOpenChange={operations.setDeleteDialogVisible}
        files={operations.deletingFiles}
        onConfirm={operations.handleDelete}
        isLoading={operations.deletePending}
      />

      <PermanentDeleteConfirmDialog
        open={operations.permanentDeleteDialogVisible}
        onOpenChange={operations.setPermanentDeleteDialogVisible}
        files={operations.permanentlyDeletingFiles}
        onConfirm={operations.handlePermanentDelete}
        isLoading={operations.permanentDeletePending}
      />

      <FileDetailModal
        open={operations.detailModalVisible}
        onOpenChange={operations.setDetailModalVisible}
        file={operations.detailFile}
        breadcrumbPath={fileList.breadcrumbPath}
      />
    </div>
  )
}
