import { useState, useEffect, useRef, type RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import type { FileItem, SortOrder } from '@/types/file'
import { Highlight } from '@/components/Highlight'
import {
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
import { formatFileListDisplayTime, formatFileSize } from '@/utils/format'
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FileIcon } from '@/components/file-icon'
import { useAppearanceStore } from '@/store/appearance'
import { useFileDragDrop } from '../hooks/useFileDragDrop'
import { FileListScrollSentinel } from './FileListScrollSentinel'

export function FileListRowActionIcon() {
  return (
    <span
      className='inline-flex flex-col items-center justify-center gap-[3px]'
      aria-hidden
    >
      <span className='size-1 rounded-full bg-current opacity-80' />
      <span className='size-1 rounded-full bg-current opacity-80' />
    </span>
  )
}

interface FileListViewProps {
  fileList: FileItem[]
  /** 当前生效的搜索关键词，用于结果高亮 */
  searchKeyword?: string
  selectedKeys: string[]
  onSelectionChange: (keys: string[]) => void
  onFileClick: (file: FileItem) => void
  onSortChange: (field: string, direction: SortOrder) => void
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
  scrollRootRef?: RefObject<HTMLElement | null>
}

export function FileListView({
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
}: FileListViewProps) {
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
  const canShare = hasPermission('file:share')

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
  }, [
    dragState.dropTargetName,
    dragState.draggedItems.length,
    onDragStateChange,
  ])

  /** 上一次普通点击的行号，Shift 范围选以它为起点 */
  const lastClickedIndexRef = useRef<number | null>(null)

  const toggleOne = (id: string) => {
    onSelectionChange(
      selectedKeys.includes(id)
        ? selectedKeys.filter((k) => k !== id)
        : [...selectedKeys, id]
    )
  }

  const handleRowClick = (
    file: FileItem,
    event: React.MouseEvent,
    index: number
  ) => {
    // 触屏多选模式：点一下就是选中/取消选中，选中态由行背景色表示
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

  // 右键未选中的行时先单选它（已选中的行保留当前多选），让高亮跟随操作对象
  const handleRowContextMenu = (file: FileItem, index: number) => {
    if (!selectedKeys.includes(file.id)) {
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

  const showNoMoreHint =
    !hasMore && !loadingMore && fileList.length > 0

  return (
    <div className='min-w-0'>
      {/* 不用 overflow-hidden：那会另建一个剪裁容器，把 sticky 表头锁死在本块内。背面背景同色，圆角本来就看不出来 */}
      <div className='rounded-xl bg-background'>
        {/*
          三列弹性铺满：table-fixed 让列宽完全由表头决定，
          大小/时间列用 em 定宽（随表头响应式字号 text-xs→text-sm 同步缩放，
          比例关系在手机/电脑上一致），名称列吃掉剩余宽度铺满整行，
          任何视口下都不产生表格内部横向滚动。
        */}
        {/* containerClassName 必须清掉自带的 overflow-auto，否则它就成了表头最近的滚动祖先 */}
        <Table className='table-fixed' containerClassName='overflow-visible'>
          {/*
            粘性表头：sticky 加在 th 而不是 thead，因为 Firefox 不支持 table-section 级 sticky。
            最近的滚动祖先是页面里那个 h-full overflow-auto 容器，所以只有文件行在滚动。
            不画表头底线，靠 th 自身不透明背景在滚动时遮住下方行完成分层。
          */}
          <TableHeader className='[&_tr]:border-0 [&_th]:sticky [&_th]:top-0 [&_th]:z-20 [&_th]:bg-background'>
            <TableRow className='border-0 hover:bg-transparent'>
              <TableHead className='text-muted-foreground h-[48px] px-2 text-left text-xs font-medium sm:px-4 sm:text-sm'>
                {t('table.colName')}
              </TableHead>
              {/* em 宽度按最长内容 1023.99 GB 设计，随响应式字号缩放 */}
              <TableHead className='text-muted-foreground h-[48px] w-[9em] px-2 text-left text-xs font-medium sm:px-4 sm:text-sm'>
                {t('table.colSize')}
              </TableHead>
              {/* 恒定 16 字符 yyyy-MM-dd HH:mm，9.3em 文本 + 列内边距 */}
              <TableHead className='text-muted-foreground h-[48px] w-[11.5em] px-2 text-left text-xs font-medium sm:px-4 sm:text-sm'>
                {t('table.colModified')}
              </TableHead>
              {/* 行尾操作列只在触屏设备上出现：鼠标端靠右键菜单 */}
              <TableHead className='text-muted-foreground h-[48px] w-10 px-1 text-right text-xs font-medium hoverable:hidden sm:text-sm'>
                <span className='sr-only'>{t('list.ariaMore')}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
        <TableBody>
          {fileList.map((file, index) => {
            const isSelected = selectedKeys.includes(file.id)
            const isDragging = dragState.draggedItems.some(
              (f) => f.id === file.id
            )
            const isDropTarget =
              file.isDir && dragState.dropTargetId === file.id
            const isMultiSelected = selectedKeys.length > 1 && isSelected
            const selectedFiles = fileList.filter((f) =>
              selectedKeys.includes(f.id)
            )
            const hasUnfavorited = selectedFiles.some((f) => !f.isFavorite)
            const downloadableFiles = selectedFiles.filter((f) => !f.isDir)
            return (
              <ContextMenu key={file.id}>
                <ContextMenuTrigger asChild>
                  <TableRow
                    data-file-id={file.id}
                    className={cn(
                      'group min-h-[48px] border-b-0 transition-colors duration-150',
                      'hover:bg-primary/[0.06]',
                      isSelected &&
                        !isDropTarget &&
                        'bg-primary/[0.08] selected',
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
                    onClick={(e) => handleRowClick(file, e, index)}
                    onContextMenu={() => handleRowContextMenu(file, index)}
                    onDoubleClick={() => handleDoubleClick(file)}
                  >
                    <TableCell
                      data-file-name-cell
                      className='min-h-[48px] align-middle px-2 py-1.5 sm:px-4'
                    >
                      <div className='flex min-w-0 items-center gap-2'>
                        <div className='flex size-8 shrink-0 items-center justify-center rounded-md bg-muted/40'>
                          {file.thumbnailUrl && thumbnailsEnabled ? (
                            <img
                              src={file.thumbnailUrl}
                              alt={file.displayName}
                              className='size-7 rounded object-cover pointer-events-none select-none'
                              draggable={false}
                              onContextMenu={(e) => e.preventDefault()}
                            />
                          ) : (
                            <FileIcon
                              type={file.isDir ? 'dir' : file.suffix || ''}
                              size={24}
                              className='shrink-0'
                            />
                          )}
                        </div>
                        <span
                          className={cn(
                            'min-w-0 line-clamp-2 break-words text-sm font-medium text-foreground/90 transition-colors',
                            'group-hover:text-primary'
                          )}
                          title={file.displayName}
                        >
                          <Highlight
                            text={file.displayName}
                            keyword={searchKeyword}
                          />
                        </span>
                      </div>
                    </TableCell>
                    <TableCell
                      className={cn(
                        'min-h-[48px] align-middle px-2 py-1.5 text-xs tabular-nums whitespace-nowrap text-muted-foreground transition-colors sm:px-4 sm:text-sm',
                        'group-hover:text-primary'
                      )}
                    >
                      {file.isDir ? '—' : formatFileSize(file.size)}
                    </TableCell>
                    <TableCell
                      className={cn(
                        'min-h-[48px] align-middle px-2 py-1.5 text-xs tabular-nums whitespace-nowrap text-muted-foreground transition-colors sm:px-4 sm:text-sm',
                        'group-hover:text-primary'
                      )}
                    >
                      {formatFileListDisplayTime(file.updateTime)}
                    </TableCell>
                    <TableCell
                      className='min-h-[48px] align-middle px-1 py-1.5 text-right hoverable:hidden'
                      onClick={(e) => e.stopPropagation()}
                    >
                      <DropdownMenu
                        modal={false}
                        onOpenChange={(open) =>
                          setOpenMenuId(open ? file.id : null)
                        }
                      >
                        <DropdownMenuTrigger asChild>
                          <Button
                            variant='ghost'
                            size='icon'
                            className={cn(
                              'size-8 rounded-lg text-muted-foreground transition-colors',
                              'hover:bg-primary/10 hover:text-primary',
                              'group-hover:text-primary',
                              openMenuId === file.id && 'bg-primary/10 text-primary'
                            )}
                            onClick={(e) => e.stopPropagation()}
                            aria-label={t('list.ariaMore')}
                          >
                            <FileListRowActionIcon />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align='end'>
                          {!file.isDir && canRead && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onPreview(file)
                              }}
                            >
                              <Eye className='size-4' />
                              {t('rowMenu.preview')}
                            </DropdownMenuItem>
                          )}
                          {!file.isDir && canWrite && onEdit && isEditableSuffix(file.suffix) && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onEdit(file)
                              }}
                            >
                              <FilePen className='size-4' />
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
                              <Share2 className='size-4' />
                              {t('rowMenu.share')}
                            </DropdownMenuItem>
                          )}
                          {!file.isDir && canRead && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onDownload(file)
                              }}
                            >
                              <Download className='size-4' />
                              {t('rowMenu.download')}
                            </DropdownMenuItem>
                          )}
                          {canWrite && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onMove(file)
                              }}
                            >
                              <Move className='size-4' />
                              {t('rowMenu.move')}
                            </DropdownMenuItem>
                          )}
                          {canWrite && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onRename(file)
                              }}
                            >
                              <Edit className='size-4' />
                              {t('rowMenu.rename')}
                            </DropdownMenuItem>
                          )}
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onDetail(file)
                            }}
                          >
                            <Info className='size-4' />
                            {t('rowMenu.detail')}
                          </DropdownMenuItem>
                          {favoriteEnabled && canWrite && (
                            <DropdownMenuItem
                              onClick={(e) => {
                                e.stopPropagation()
                                onFavorite(file)
                              }}
                            >
                              <Heart
                                className={cn(
                                  'size-4',
                                  file.isFavorite && 'fill-current text-red-500'
                                )}
                              />
                              {file.isFavorite
                                ? t('rowMenu.unfavorite')
                                : t('rowMenu.favorite')}
                            </DropdownMenuItem>
                          )}
                          {canWrite && (
                            <>
                              <DropdownMenuSeparator className='bg-border' />
                              <DropdownMenuItem
                                onClick={(e) => {
                                  e.stopPropagation()
                                  onDelete(file)
                                }}
                              >
                                <Trash2 className='size-4' />
                                {t('rowMenu.delete')}
                              </DropdownMenuItem>
                            </>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
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
                          <ContextMenuItem
                            onClick={(e) => {
                              e.stopPropagation()
                              onPermanentDelete(file)
                            }}
                            className='text-destructive focus:text-destructive'
                          >
                            <Trash2 className='mr-2 h-4 w-4' />
                            {t('rowMenu.deleteForever')}
                          </ContextMenuItem>
                        </>
                      )}
                    </>
                  )}
                </ContextMenuContent>
              </ContextMenu>
            )
          })}
        </TableBody>
          </Table>
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
