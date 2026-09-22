import { useState, useEffect, useRef, type RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import type { FileItem } from '@/types/file'
import { Highlight } from '@/components/Highlight'
import {
  MoreHorizontal,
  Download,
  Share2,
  Heart,
  Move,
  Trash2,
  Edit,
  Eye,
  FilePen,
  Info,
  Loader2,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { formatFileListDisplayTime } from '@/utils/format'
import { isEditableSuffix } from '@/utils/preview-types'
import { usePermission } from '@/hooks/use-permission'
import { useFeatureStore } from '@/store/feature'
import { Button } from '@/components/ui/button'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { FileIcon } from '@/components/file-icon'
import { useAppearanceStore } from '@/store/appearance'
import { useFileDragDrop } from '../hooks/useFileDragDrop'
import { FileListScrollSentinel } from './FileListScrollSentinel'

interface FileGridViewProps {
  fileList: FileItem[]
  /** 当前生效的搜索关键词，用于结果高亮 */
  searchKeyword?: string
  selectedKeys: string[]
  onSelectionChange: (keys: string[]) => void
  onFileClick: (file: FileItem) => void
  onDownload: (file: FileItem | FileItem[]) => void
  onShare: (file: FileItem) => void
  onDelete: (file: FileItem) => void
  onRename: (file: FileItem) => void
  onMove: (file: FileItem) => void
  onMoveFiles: (fileIds: string[], targetDirId: string) => Promise<void>
  onFavorite: (file: FileItem | FileItem[]) => void
  onPreview: (file: FileItem) => void
  onEdit?: (file: FileItem) => void
  onDetail: (file: FileItem) => void
  onDragStateChange?: (
    dropTargetName: string | null,
    draggedCount: number
  ) => void
  onBatchShare?: (files: FileItem[]) => void
  onBatchMove?: (files: FileItem[]) => void
  onBatchDelete?: (files: FileItem[]) => void
  /** 触屏多选模式：点击即切换选中，无需 Ctrl 键 */
  selectMode?: boolean
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => void
  /** 主内容区滚动容器，用于触底自动加载 */
  scrollRootRef?: RefObject<HTMLElement | null>
}

export function FileGridView({
  fileList,
  searchKeyword,
  selectedKeys,
  onSelectionChange,
  onFileClick,
  onDownload,
  onShare,
  onDelete,
  onRename,
  onMove,
  onMoveFiles,
  onFavorite,
  onPreview,
  onEdit,
  onDetail,
  onDragStateChange,
  onBatchShare,
  onBatchMove,
  onBatchDelete,
  selectMode = false,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  scrollRootRef,
}: FileGridViewProps) {
  const { t } = useTranslation('files')
  // 收藏功能未开启时，隐藏收藏入口（与侧边栏「收藏」菜单同开关）
  const favoriteEnabled = useFeatureStore((s) => !!s.toggles.favorite)
  const thumbnailsEnabled = useAppearanceStore(
    (state) => state.thumbnailsEnabled
  )
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)
  const { hasPermission } = usePermission()
  const canRead = hasPermission('file:read')
  const canWrite = hasPermission('file:write')
  // 分享功能开关关闭（或缺省）时隐藏分享入口
  const shareEnabled = useFeatureStore((s) => !!s.toggles.share)
  const canShare = shareEnabled && hasPermission('file:share')

  // 提到 map 外部，避免每个 item 重复计算
  const selectedSet = new Set(selectedKeys)
  const selectedFiles = fileList.filter((f) => selectedSet.has(f.id))
  const hasUnfavorited = selectedFiles.some((f) => !f.isFavorite)
  const downloadableFiles = selectedFiles.filter((f) => !f.isDir)

  // 拖拽功能
  const {
    dragState,
    handleDragStart,
    handleDragEnd,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  } = useFileDragDrop(selectedKeys, fileList, onMoveFiles)

  // 通知父组件拖拽状态变化
  useEffect(() => {
    if (onDragStateChange) {
      onDragStateChange(dragState.dropTargetName, dragState.draggedItems.length)
    }
    // onDragStateChange 是父组件传入的稳定引用，不加入依赖以避免无效重跑
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dragState.dropTargetName, dragState.draggedItems.length])

  /** 上一次普通点击的卡片序号，Shift 范围选以它为起点 */
  const lastClickedIndexRef = useRef<number | null>(null)

  const toggleOne = (id: string) => {
    onSelectionChange(
      selectedSet.has(id)
        ? selectedKeys.filter((k) => k !== id)
        : [...selectedKeys, id]
    )
  }

  const handleItemClick = (
    file: FileItem,
    event: React.MouseEvent,
    index: number
  ) => {
    // 触屏多选模式：点一下就是选中/取消选中，选中态由卡片背景色表达
    if (selectMode) {
      toggleOne(file.id)
      lastClickedIndexRef.current = index
      return
    }

    // Shift + 点击：从上次点击的位置到当前位置做范围选
    if (event.shiftKey && lastClickedIndexRef.current !== null) {
      event.preventDefault()
      const [from, to] = [lastClickedIndexRef.current, index].sort(
        (a, b) => a - b
      )
      const rangeIds = fileList.slice(from, to + 1).map((f) => f.id)
      onSelectionChange(Array.from(new Set([...selectedKeys, ...rangeIds])))
      return
    }

    // Ctrl/Cmd + 点击：切换当前项状态
    if (event.ctrlKey || event.metaKey) {
      toggleOne(file.id)
      lastClickedIndexRef.current = index
      return
    }

    // 普通左键点击：仅选中当前项
    onSelectionChange([file.id])
    lastClickedIndexRef.current = index
  }

  // 右键未选中的卡片时先单选它（已选中的保留当前多选），让高亮跟随操作对象
  const handleItemContextMenu = (file: FileItem, index: number) => {
    if (!selectedSet.has(file.id)) {
      onSelectionChange([file.id])
      lastClickedIndexRef.current = index
    }
  }

  const handleDoubleClick = (file: FileItem) => {
    if (file.isDir) {
      onFileClick(file)
    } else if (canRead) {
      onPreview(file)
    }
  }

  /** 已全部拉取且无加载中时展示（与常见网盘底部提示一致） */
  const showNoMoreHint =
    !hasMore && !loadingMore && fileList.length > 0

  return (
    // 外层滚动区已有内边距，窄屏不再叠加，给卡片让出宽度；底部留白交给滚动区，让「没有更多了」提示距底与其他列表一致
    <div className='p-0 sm:px-4 sm:pt-4'>
      <div className='relative'>
        <div className='grid grid-cols-[repeat(auto-fill,minmax(130px,1fr))] gap-4'>
        {fileList.map((file, index) => {
          const isSelected = selectedSet.has(file.id)
          const isDragging = dragState.draggedItems.some(
            (f) => f.id === file.id
          )
          const isDropTarget = file.isDir && dragState.dropTargetId === file.id
          const isMultiSelected = selectedKeys.length > 1 && isSelected

          return (
            <ContextMenu key={file.id}>
              <ContextMenuTrigger asChild>
                <div
                  data-file-id={file.id}
                  className={cn(
                    'group relative cursor-pointer rounded-lg p-4 pb-2 text-center transition-all',
                    'hover:bg-accent',
                    isSelected && 'bg-primary/10 selected',
                    isDragging && 'cursor-move opacity-50',
                    isDropTarget && 'bg-primary/15 is-folder-drop-target'
                  )}
                  draggable={canWrite && !openMenuId}
                  onDragStart={(e) => canWrite && handleDragStart(e, file)}
                  onDragEnd={() => canWrite && handleDragEnd()}
                  onDragEnter={(e) => canWrite && handleDragEnter(e, file)}
                  onDragOver={(e) => canWrite && handleDragOver(e, file)}
                  onDragLeave={(e) => canWrite && handleDragLeave(e, file)}
                  onDrop={(e) => canWrite && handleDrop(e, file)}
                  onClick={(e) => handleItemClick(file, e, index)}
                  onContextMenu={() => handleItemContextMenu(file, index)}
                  onDoubleClick={() => handleDoubleClick(file)}
                >
                  {/* 更多操作 */}
                  <div
                    className={cn(
                      'absolute top-2 right-2 z-10 transition-opacity',
                      openMenuId === file.id
                        ? 'opacity-100'
                        : 'opacity-100 hoverable:opacity-0 hoverable:group-hover:opacity-100'
                    )}
                    style={{ visibility: isSelected ? 'hidden' : 'visible' }}
                  >
                    <DropdownMenu
                      modal={false}
                      onOpenChange={(open) =>
                        setOpenMenuId(open ? file.id : null)
                      }
                    >
                      <DropdownMenuTrigger
                        asChild
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Button
                          variant='ghost'
                          size='icon'
                          className='h-7 w-7 bg-background/95 shadow-sm backdrop-blur-sm hover:scale-105 hover:bg-background hover:shadow-md'
                          onClick={(e) => e.stopPropagation()}
                        >
                          <MoreHorizontal className='h-4 w-4' />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align='end'>
                        {!file.isDir && canRead && (
                          <>
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onPreview(file)
                              }}
                            >
                              <Eye className='mr-2 h-4 w-4' />
                              {t('rowMenu.preview')}
                            </DropdownMenuItem>
                          </>
                        )}
                        {!file.isDir && canWrite && onEdit && isEditableSuffix(file.suffix) && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onEdit(file)
                            }}
                          >
                            <FilePen className='mr-2 h-4 w-4' />
                            {t('rowMenu.edit')}
                          </DropdownMenuItem>
                        )}
                        {canShare && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onShare(file)
                            }}
                          >
                            <Share2 className='mr-2 h-4 w-4' />
                            {t('rowMenu.share')}
                          </DropdownMenuItem>
                        )}
                        {favoriteEnabled && canWrite && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onFavorite(file)
                            }}
                          >
                            <Heart
                              className={cn(
                                'mr-2 h-4 w-4',
                                file.isFavorite && 'fill-current text-red-500'
                              )}
                            />
                            {file.isFavorite
                              ? t('rowMenu.unfavorite')
                              : t('rowMenu.favorite')}
                          </DropdownMenuItem>
                        )}
                        {!file.isDir && canRead && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onDownload(file)
                            }}
                          >
                            <Download className='mr-2 h-4 w-4' />
                            {t('rowMenu.download')}
                          </DropdownMenuItem>
                        )}
                        {canWrite && <DropdownMenuSeparator />}
                        {canWrite && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onRename(file)
                            }}
                          >
                            <Edit className='mr-2 h-4 w-4' />
                            {t('rowMenu.rename')}
                          </DropdownMenuItem>
                        )}
                        {canWrite && (
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onMove(file)
                            }}
                          >
                            <Move className='mr-2 h-4 w-4' />
                            {t('rowMenu.move')}
                          </DropdownMenuItem>
                        )}
                        <DropdownMenuItem
                          onClick={(e) => {
                            e.stopPropagation()
                            onDetail(file)
                          }}
                        >
                          <Info className='mr-2 h-4 w-4' />
                          {t('rowMenu.detail')}
                        </DropdownMenuItem>
                        {canWrite && (
                          <>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onDelete(file)
                              }}
                            >
                              <Trash2 className='mr-2 h-4 w-4' />
                              {t('rowMenu.delete')}
                            </DropdownMenuItem>
                          </>
                        )}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>

                  {/* 缩略图 95×75；文件夹同宽，略增高以容纳 Folder 顶部标签（勿 overflow-hidden） */}
                  <div className='mb-3 flex min-h-[90px] items-center justify-center overflow-visible pt-1'>
                    {file.thumbnailUrl && thumbnailsEnabled ? (
                      <div className='h-[75px] w-[95px] shrink-0 overflow-hidden rounded-md shadow-sm transition-transform group-hover:scale-[1.02]'>
                        <img
                          src={file.thumbnailUrl}
                          alt={file.displayName}
                          className='h-full w-full object-cover object-center pointer-events-none select-none'
                          draggable={false}
                          onContextMenu={(e) => e.preventDefault()}
                        />
                      </div>
                    ) : file.isDir ? (
                      <div className='flex w-[95px] shrink-0 items-center justify-center overflow-visible pt-1'>
                        <FileIcon
                          type='dir'
                          size={86}
                          className='transition-transform group-hover:scale-[1.02]'
                        />
                      </div>
                    ) : (
                      <FileIcon
                        type={file.suffix || ''}
                        size={56}
                        className='transition-transform group-hover:scale-105'
                      />
                    )}
                  </div>

                  {/* 文件名 */}
                  <div
                    className='mb-1 line-clamp-2 break-words px-1 text-center text-sm leading-snug font-normal text-foreground'
                    title={file.displayName}
                  >
                    <Highlight text={file.displayName} keyword={searchKeyword} />
                  </div>

                  {/* 修改时间：与列表视图同一套固定格式，不让网格出现「今天」而列表出现日期 */}
                  <div className='text-xs tabular-nums whitespace-nowrap text-muted-foreground'>
                    {formatFileListDisplayTime(file.updateTime)}
                  </div>
                </div>
              </ContextMenuTrigger>
              <ContextMenuContent>
                {isMultiSelected ? (
                  // 多选菜单
                  <>
                    {canRead && downloadableFiles.length > 0 && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onDownload(downloadableFiles)
                        }}
                      >
                        <Download className='mr-2 h-4 w-4' />
                        {t('rowMenu.download')}
                      </ContextMenuItem>
                    )}
                    {canShare && onBatchShare && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onBatchShare(selectedFiles)
                        }}
                      >
                        <Share2 className='mr-2 h-4 w-4' />
                        {t('rowMenu.share')}
                      </ContextMenuItem>
                    )}
                    {favoriteEnabled && canWrite && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onFavorite(selectedFiles)
                        }}
                      >
                        <Heart
                          className={cn(
                            'mr-2 h-4 w-4',
                            !hasUnfavorited && 'fill-current text-red-500'
                          )}
                        />
                        {hasUnfavorited
                          ? t('rowMenu.favorite')
                          : t('rowMenu.unfavorite')}
                      </ContextMenuItem>
                    )}
                    {canWrite && <ContextMenuSeparator />}
                    {canWrite && onBatchMove && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onBatchMove(selectedFiles)
                        }}
                      >
                        <Move className='mr-2 h-4 w-4' />
                        {t('rowMenu.move')}
                      </ContextMenuItem>
                    )}
                    {canWrite && onBatchDelete && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onBatchDelete(selectedFiles)
                        }}
                      >
                        <Trash2 className='mr-2 h-4 w-4' />
                        {t('rowMenu.delete')}
                      </ContextMenuItem>
                    )}
                  </>
                ) : (
                  // 单选菜单
                  <>
                    {!file.isDir && canRead && (
                      <>
                        <ContextMenuItem
                          onClick={(e) => {
                            e.stopPropagation()
                            onPreview(file)
                          }}
                        >
                          <Eye className='mr-2 h-4 w-4' />
                          {t('rowMenu.preview')}
                        </ContextMenuItem>
                      </>
                    )}
                    {!file.isDir && canWrite && onEdit && isEditableSuffix(file.suffix) && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onEdit(file)
                        }}
                      >
                        <FilePen className='mr-2 h-4 w-4' />
                        {t('rowMenu.edit')}
                      </ContextMenuItem>
                    )}
                    {canShare && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onShare(file)
                        }}
                      >
                        <Share2 className='mr-2 h-4 w-4' />
                        {t('rowMenu.share')}
                      </ContextMenuItem>
                    )}
                    {favoriteEnabled && canWrite && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onFavorite(file)
                        }}
                      >
                        <Heart
                          className={cn(
                            'mr-2 h-4 w-4',
                            file.isFavorite && 'fill-current text-red-500'
                          )}
                        />
                        {file.isFavorite
                          ? t('rowMenu.unfavorite')
                          : t('rowMenu.favorite')}
                      </ContextMenuItem>
                    )}
                    {!file.isDir && canRead && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onDownload(file)
                        }}
                      >
                        <Download className='mr-2 h-4 w-4' />
                        {t('rowMenu.download')}
                      </ContextMenuItem>
                    )}
                    {canWrite && <ContextMenuSeparator />}
                    {canWrite && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onRename(file)
                        }}
                      >
                        <Edit className='mr-2 h-4 w-4' />
                        {t('rowMenu.rename')}
                      </ContextMenuItem>
                    )}
                    {canWrite && (
                      <ContextMenuItem
                        onClick={(e) => {
                          e.stopPropagation()
                          onMove(file)
                        }}
                      >
                        <Move className='mr-2 h-4 w-4' />
                        {t('rowMenu.move')}
                      </ContextMenuItem>
                    )}
                    <ContextMenuItem
                      onClick={(e) => {
                        e.stopPropagation()
                        onDetail(file)
                      }}
                    >
                      <Info className='mr-2 h-4 w-4' />
                      {t('rowMenu.detail')}
                    </ContextMenuItem>
                    {canWrite && (
                      <>
                        <ContextMenuSeparator />
                        <ContextMenuItem
                          onClick={(e) => {
                            e.stopPropagation()
                            onDelete(file)
                          }}
                        >
                          <Trash2 className='mr-2 h-4 w-4' />
                          {t('rowMenu.delete')}
                        </ContextMenuItem>
                      </>
                    )}
                  </>
                )}
              </ContextMenuContent>
            </ContextMenu>
          )
        })}
        </div>
      </div>
      {scrollRootRef && onLoadMore && (
        <FileListScrollSentinel
          scrollRootRef={scrollRootRef}
          hasMore={!!hasMore}
          onLoadMore={onLoadMore}
        />
      )}
      {loadingMore && (
        <div className='flex justify-center py-4'>
          <Loader2
            className='h-5 w-5 shrink-0 animate-spin text-muted-foreground'
            aria-hidden
          />
          <span className='sr-only'>{t('list.loading')}</span>
        </div>
      )}
      {showNoMoreHint && (
        <p className='pt-6 text-center text-sm text-muted-foreground/55'>
          {t('index.noMore')}
        </p>
      )}
    </div>
  )
}
