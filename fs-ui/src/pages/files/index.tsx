import { useState, useEffect, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { FileItem } from '@/types/file'
import {
  List,
  LayoutGrid,
  FileText,
  FilePlus,
  Upload,
  FolderPlus,
  RefreshCw,
} from 'lucide-react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { NoPermission } from '@/components/no-permission'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import { usePermission } from '@/hooks/use-permission'
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import {
  Toolbar,
  FileBreadcrumb,
  FileGridView,
  FileListView,
  CreateFolderModal,
  CreateTextModal,
  TextEditorModal,
  RenameModal,
  MoveModal,
  ShareModal,
  FileBulkSelectionBar,
  RecycleBinView,
  DeleteConfirmDialog,
  FileDetailModal,
  MySharesView,
} from './components'
import UploadModal from './components/UploadModal'
import UploadPanel from './components/UploadPanel'
import { useExternalFileDrop } from './hooks/useExternalFileDrop'
import { useFileList } from './hooks/useFileList'
import { useFileOperations } from './hooks/useFileOperations'
import { useMarqueeSelection } from './hooks/useMarqueeSelection'

type ViewMode = 'list' | 'grid'

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

  const fileScrollAreaRef = useRef<HTMLDivElement>(null)

  const fileList = useFileList()

  /**
   * 清空选中
   */
  const clearSelection = () => {
    setSelectedKeys([])
  }

  // 鼠标框选：仅从列表空白处起拖，拖出的矩形实时决定选中项
  const { marqueeRect, onMarqueePointerDown, shouldSuppressClick } =
    useMarqueeSelection({
      containerRef: fileScrollAreaRef,
      enabled: !fileList.loading && fileList.fileList.length > 0,
      onChange: setSelectedKeys,
    })

  const operations = useFileOperations(fileList.refresh, clearSelection, () => {
    // 在特殊视图中创建文件夹后，返回全部文件页面
    if (isFavoritesView || isRecentsView || isTypeFilter || isDirFilter) {
      navigate(`/files?viewMode=${viewMode}`)
    }
  }, fileList.updateFileItems)

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
  }, [
    isFavoritesView,
    isRecentsView,
    isDirFilter,
    fileType,
    t,
  ])

  // 判断文件夹
  const selectedFiles = fileList.fileList.filter((file) =>
    selectedKeys.includes(file.id)
  )
  const hasUnfavorited = selectedFiles.some((f) => !f.isFavorite)

  /**
   * 键盘快捷键
   */
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // ESC 键退出多选模式并取消选中
      if (e.key === 'Escape') {
        if (selectedKeys.length > 0) clearSelection()
        if (selectMode) setSelectMode(false)
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
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [selectedKeys, fileList.fileList, canWrite, selectMode])

  /**
   * 当目录变化时清空选中状态并退出多选模式
   */
  useEffect(() => {
    clearSelection()
    setSelectMode(false)
  }, [fileList.currentParentId, viewType, fileType, isDirFilter])

  /**
   * 监听文件上传完成事件
   */
  useEffect(() => {
    const handleUploadComplete = (event: Event) => {
      const customEvent = event as CustomEvent<{ parentId?: string }>
      const { parentId } = customEvent.detail

      // 如果是当前目录的文件，刷新列表
      if (parentId === fileList.currentParentId) {
        fileList.refresh()
      }
    }

    window.addEventListener('file-upload-complete', handleUploadComplete)

    return () => {
      window.removeEventListener('file-upload-complete', handleUploadComplete)
    }
  }, [fileList.currentParentId, fileList.refresh])

  /**
   * 打开上传弹窗
   */
  const handleOpenUploadModal = () => {
    if (!canWrite) return
    setUploadModalOpen(true)
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
   * 批量操作
   */
  const handleBatchDownload = () => {
    const downloadableFiles = selectedFiles.filter((f) => !f.isDir)
    if (downloadableFiles.length === 0) {
      toast.warning(t('index.toastNoDownload'))
      return
    }
    operations.handleDownload(downloadableFiles)
    clearSelection()
  }

  const handleBatchRename = () => {
    if (!canWrite) return
    if (selectedFiles.length !== 1) return
    operations.openRenameModal(selectedFiles[0])
  }

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
    draggedCount: number
  ) => {
    setDragTargetName(dropTargetName)
    setDraggedCount(draggedCount)
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
      {/* 顶部工具栏：窄屏时标题与工具栏各占一行，分隔线两端内缩与内容对齐 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 py-3 sm:px-6 sm:py-4'>
        {/* 面包屑导航 */}
        <div className='w-full min-w-0 sm:w-auto sm:flex-1'>
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
        </div>

        {/* 右侧工具栏 */}
        <Toolbar
          searchKeyword={fileList.searchInput}
          onSearchChange={fileList.setSearchInput}
          onSearch={fileList.commitSearch}
          onUpload={handleOpenUploadModal}
          onCreateFolder={operations.openCreateFolderModal}
          onCreateText={operations.openCreateTextModal}
          onRefresh={fileList.refresh}
          hideActions={!isAllFilesView}
          selectMode={selectMode}
          onToggleSelectMode={
            fileList.fileList.length > 0
              ? () => setSelectMode((v) => !v)
              : undefined
          }
        />
      </div>

      {/* 主内容区域：顶部留白放在这一层而不是滚动容器里，表头才能一上来就贴住工具栏、没有上浮行程 */}
      <div className='relative flex-1 overflow-hidden pt-3 sm:pt-6'>
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
              ) : (
                /* 不撑满高度：内容不足时强撑到 100% 会因亚像素取整虚报溢出，白得一个滚动条 */
                <div className='min-h-0'>
                  {viewMode === 'grid' ? (
                    <FileGridView
                      fileList={fileList.fileList}
                      selectedKeys={selectedKeys}
                      onSelectionChange={setSelectedKeys}
                      onFileClick={handleFileClick}
                      onDownload={operations.handleDownload}
                      onShare={operations.openShareModal}
                      onDelete={operations.openDeleteConfirm}
                      onRename={operations.openRenameModal}
                      onMove={operations.openMoveModal}
                      onMoveFiles={handleMoveFiles}
                      onFavorite={operations.handleFavorite}
                      onPreview={operations.openPreview}
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
                      selectedKeys={selectedKeys}
                      onSelectionChange={setSelectedKeys}
                      onFileClick={handleFileClick}
                      onSortChange={fileList.handleSortChange}
                      onDownload={operations.handleDownload}
                      onShare={operations.openShareModal}
                      onDelete={operations.openDeleteConfirm}
                      onRename={operations.openRenameModal}
                      onMove={operations.openMoveModal}
                      onMoveFiles={handleMoveFiles}
                      onFavorite={operations.handleFavorite}
                      onPreview={operations.openPreview}
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
              <ContextMenuItem onClick={operations.openCreateTextModal}>
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
            {canWrite && (
              <ContextMenuSeparator />
            )}
            <ContextMenuItem onClick={() => fileList.refresh()}>
              <RefreshCw className='mr-2 h-4 w-4' />
              {t('index.refresh')}
            </ContextMenuItem>
          </ContextMenuContent>
        </ContextMenu>

        {/* 系统文件拖入提示：drop 才真正入队，此层只做视觉且不拦截指针 */}
        {externalDrop.isDragging && (
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

      {/* 底部状态栏：全选、统计信息和视图切换，置于文件区最下方（无分隔线） */}
      <div className='flex shrink-0 flex-wrap items-center justify-between gap-x-3 gap-y-2 px-3 py-2.5 sm:px-6'>
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
          <ToggleGroupItem value='list' aria-label={t('index.ariaList')} size='sm'>
            <List className='h-4 w-4' />
          </ToggleGroupItem>
          <ToggleGroupItem value='grid' aria-label={t('index.ariaGrid')} size='sm'>
            <LayoutGrid className='h-4 w-4' />
          </ToggleGroupItem>
        </ToggleGroup>
      </div>

      {/* 拖拽移动提示：fixed 底部，避免插入文档流导致布局抖动 */}
      {dragTargetName && (
        <div
          className={cn(
            'pointer-events-none fixed left-1/2 z-[90] -translate-x-1/2 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 shadow-md dark:border-blue-900 dark:bg-blue-950/40',
            selectedKeys.length > 0 ? 'bottom-28' : 'bottom-16'
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
              {t('index.dragMoveTo', { name: dragTargetName })}{' '}
              {draggedCount > 1 &&
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
        onDownload={handleBatchDownload}
        onRename={handleBatchRename}
        onShare={handleBatchShare}
        onFavorite={handleBatchFavorite}
        onMove={handleBatchMove}
        onDelete={handleBatchDelete}
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
        parentId={fileList.currentParentId}
        onConfirm={operations.handleCreateText}
      />

      <TextEditorModal
        open={operations.textEditorVisible}
        onOpenChange={operations.setTextEditorVisible}
        file={operations.editingTextFile}
        onSuccess={fileList.refresh}
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
