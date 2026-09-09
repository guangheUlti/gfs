import { useState, useEffect, useMemo, useCallback, useRef } from 'react'
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from '@tanstack/react-table'
import type { FileRecycleItem } from '@/types/file'
import { Undo2, Trash2, FileText } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import {
  getRecyclePage,
  restoreFiles,
  permanentDeleteFiles,
  clearRecycle,
} from '@/api/file'
import { cn } from '@/lib/utils'
import { formatFileSize, formatFileListDisplayTime } from '@/utils/format'
import { usePermission } from '@/hooks/use-permission'
import { useToolbarSearch } from '@/hooks/useToolbarSearch'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
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
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { BulkSelectionBar } from '@/components/bulk-selection-bar'
import { DataTablePagination } from '@/components/data-table'
import { FileIcon } from '@/components/file-icon'
import { FileBreadcrumb } from './FileBreadcrumb'
import { FileListRowActionIcon } from './FileListView'
import { Toolbar } from './Toolbar'

const RECYCLE_TABLE_HEAD: Record<string, string> = {
  displayName: '',
  size: 'w-32',
  deletedTime: 'w-48',
  // 行尾操作列只在触屏出现，鼠标端靠右键菜单；表头用 sr-only 占位
  actions: 'w-10 px-1 text-right hoverable:hidden',
}

export default function RecycleBinView() {
  const { t } = useTranslation('files')
  const { hasPermission } = usePermission()
  const canWrite = hasPermission('file:write')
  const canRestore = canWrite
  const canDelete = canWrite
  const canClear = canWrite
  const canOperateRecycle = canWrite

  const [openMenuId, setOpenMenuId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [fileList, setFileList] = useState<FileRecycleItem[]>([])
  const [total, setTotal] = useState(0)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  // 触屏多选模式：无 Ctrl/右键，进入后点行即切换选中
  const [selectMode, setSelectMode] = useState(false)
  const { searchInput, setSearchInput, searchKeyword, commitSearch } =
    useToolbarSearch('keyword')
  const [pagination, setPagination] = useState({ pageIndex: 0, pageSize: 10 })
  const prevSearchKeyword = useRef(searchKeyword)

  const [restoreDialogOpen, setRestoreDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [clearDialogOpen, setClearDialogOpen] = useState(false)
  const [operatingItem, setOperatingItem] = useState<{
    id: string
    name: string
  } | null>(null)

  const fetchRecyclePage = useCallback(async () => {
    setLoading(true)
    try {
      const result = await getRecyclePage({
        keyword: searchKeyword || undefined,
        page: pagination.pageIndex + 1,
        pageSize: pagination.pageSize,
      })
      setFileList(result.records)
      setTotal(Number(result.total ?? 0))
    } finally {
      setLoading(false)
    }
  }, [searchKeyword, pagination.pageIndex, pagination.pageSize])

  const handleRefresh = () => {
    void fetchRecyclePage()
  }

  const handleRestoreSingle = (fileId: string, fileName: string) => {
    setOperatingItem({ id: fileId, name: fileName })
    setRestoreDialogOpen(true)
  }

  const confirmRestore = async () => {
    const ids = operatingItem ? [operatingItem.id] : selectedIds
    if (ids.length === 0) return

    try {
      await restoreFiles(ids)
      toast.success(t('recycle.toastRestoreMany', { count: ids.length }))
      setSelectedIds([])
      setOperatingItem(null)
      void fetchRecyclePage()
    } finally {
      // noop
    }
  }

  const handleBatchRestore = () => {
    if (selectedIds.length === 0) return
    setOperatingItem(null)
    setRestoreDialogOpen(true)
  }

  const handleDeleteSingle = (fileId: string, fileName: string) => {
    setOperatingItem({ id: fileId, name: fileName })
    setDeleteDialogOpen(true)
  }

  const confirmDelete = async () => {
    const ids = operatingItem ? [operatingItem.id] : selectedIds
    if (ids.length === 0) return

    try {
      await permanentDeleteFiles(ids)
      toast.success(t('recycle.toastDeleteMany', { count: ids.length }))
      setSelectedIds([])
      setOperatingItem(null)
      void fetchRecyclePage()
    } finally {
      // noop
    }
  }

  const handleBatchDelete = () => {
    if (selectedIds.length === 0) return
    setOperatingItem(null)
    setDeleteDialogOpen(true)
  }

  const handleClearRecycle = () => {
    setClearDialogOpen(true)
  }

  const confirmClearRecycle = async () => {
    try {
      await clearRecycle()
      toast.success(t('recycle.toastEmpty'))
      setClearDialogOpen(false)
      setSelectedIds([])
      commitSearch('')
      setPagination((p) => ({ ...p, pageIndex: 0 }))
      void fetchRecyclePage()
    } catch {
      // 失败时重新拉取，恢复真实数据
      void fetchRecyclePage()
    }
  }

  const clearSelection = () => {
    setSelectedIds([])
  }

  const handleSelectAll = (checked: boolean) => {
    const ids = fileList.map((f) => f.id)
    setSelectedIds((prev) => {
      if (checked) {
        return [...new Set([...prev, ...ids])]
      }
      return prev.filter((id) => !ids.includes(id))
    })
  }

  /** 上一次普通点击的行号，Shift 范围选以它为起点（与文件列表同款交互） */
  const lastClickedIndexRef = useRef<number | null>(null)

  const handleRowClick = (
    rowIndex: number,
    id: string,
    event: React.MouseEvent
  ) => {
    if (selectMode) {
      setSelectedIds((prev) =>
        prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
      )
      lastClickedIndexRef.current = rowIndex
      return
    }
    if (event.shiftKey && lastClickedIndexRef.current !== null) {
      event.preventDefault()
      const [from, to] = [lastClickedIndexRef.current, rowIndex].sort(
        (a, b) => a - b
      )
      const rangeIds = fileList.slice(from, to + 1).map((f) => f.id)
      setSelectedIds((prev) => Array.from(new Set([...prev, ...rangeIds])))
      return
    }
    if (event.ctrlKey || event.metaKey) {
      setSelectedIds((prev) =>
        prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
      )
      lastClickedIndexRef.current = rowIndex
      return
    }
    setSelectedIds([id])
    lastClickedIndexRef.current = rowIndex
  }

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        clearSelection()
        setSelectMode(false)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  useEffect(() => {
    const keywordChanged = prevSearchKeyword.current !== searchKeyword
    prevSearchKeyword.current = searchKeyword

    if (keywordChanged && pagination.pageIndex !== 0) {
      setPagination((p) => ({ ...p, pageIndex: 0 }))
      return
    }

    void fetchRecyclePage()
  }, [
    searchKeyword,
    pagination.pageIndex,
    pagination.pageSize,
    fetchRecyclePage,
  ])

  const pageIds = fileList.map((f) => f.id)
  const isAllPageSelected =
    pageIds.length > 0 && pageIds.every((id) => selectedIds.includes(id))

  const columns = useMemo<ColumnDef<FileRecycleItem>[]>(
    () => [
      {
        accessorKey: 'displayName',
        header: t('recycle.colName'),
        cell: ({ row }) => {
          const file = row.original
          if (!canOperateRecycle) {
            return null
          }
          return (
            <div className='flex items-center gap-3'>
              <div className='flex h-8 w-8 items-center justify-center rounded'>
                <FileIcon
                  type={file.isDir ? 'dir' : file.suffix || ''}
                  size={28}
                  className='shrink-0'
                />
              </div>
              <span className='truncate text-sm font-normal text-foreground/90'>
                {file.displayName}
              </span>
            </div>
          )
        },
      },
      {
        accessorKey: 'size',
        header: t('recycle.colSize'),
        cell: ({ row }) => (
          <span className='text-sm text-muted-foreground'>
            {row.original.isDir ? '-' : formatFileSize(row.original.size)}
          </span>
        ),
      },
      {
        accessorKey: 'deletedTime',
        header: t('recycle.colDeleted'),
        cell: ({ row }) => (
          <span className='text-sm whitespace-nowrap text-muted-foreground tabular-nums'>
            {formatFileListDisplayTime(row.original.deletedTime)}
          </span>
        ),
      },
      {
        id: 'actions',
        header: () => (
          <span className='sr-only'>{t('recycle.colActions')}</span>
        ),
        cell: ({ row }) => {
          const file = row.original
          return (
            <div className='text-center' onClick={(e) => e.stopPropagation()}>
              <DropdownMenu
                modal={false}
                onOpenChange={(open) => setOpenMenuId(open ? file.id : null)}
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
                    aria-label={t('recycle.ariaMore')}
                  >
                    <FileListRowActionIcon />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align='end'>
                  {canRestore && (
                    <DropdownMenuItem
                      onClick={(e) => {
                        e.stopPropagation()
                        handleRestoreSingle(file.id, file.displayName)
                      }}
                    >
                      <Undo2 className='size-4' />
                      {t('recycle.menuRestore')}
                    </DropdownMenuItem>
                  )}
                  {canRestore && canDelete && <DropdownMenuSeparator />}
                  {canDelete && (
                    <DropdownMenuItem
                      className='text-destructive focus:text-destructive'
                      onClick={(e) => {
                        e.stopPropagation()
                        handleDeleteSingle(file.id, file.displayName)
                      }}
                    >
                      <Trash2 className='size-4' />
                      {t('recycle.menuDeleteForever')}
                    </DropdownMenuItem>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )
        },
        enableSorting: false,
      },
    ],
    [canDelete, canOperateRecycle, canRestore, fileList, openMenuId, t]
  )

  const pageCount = Math.max(
    1,
    Math.ceil(total / Math.max(1, pagination.pageSize))
  )

  const table = useReactTable({
    data: fileList,
    columns,
    state: { pagination },
    onPaginationChange: setPagination,
    manualPagination: true,
    pageCount,
    rowCount: total,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.id,
    enableSorting: false,
  })

  return (
    <div className='flex h-full flex-col'>
      {/* 顶部工具栏：窄屏时标题独占一行，搜索与清空按钮同处第二行 */}
      <div className='inset-divider flex flex-wrap items-center gap-x-4 gap-y-3 px-3 py-3 sm:px-6 sm:py-4'>
        <div className='w-full min-w-0 sm:w-auto sm:flex-1'>
          <FileBreadcrumb
            breadcrumbPath={[]}
            customTitle={t('recycle.title')}
            onNavigate={() => {}}
          />
        </div>

        <div className='flex w-full items-center gap-2 sm:w-auto'>
          <Toolbar
            searchKeyword={searchInput}
            onSearchChange={setSearchInput}
            onSearch={commitSearch}
            onUpload={() => {}}
            onCreateFolder={() => {}}
            onRefresh={handleRefresh}
            hideActions={true}
            selectMode={selectMode}
            onToggleSelectMode={
              fileList.length > 0 ? () => setSelectMode((v) => !v) : undefined
            }
          />

          <Button
            variant='destructive'
            size='sm'
            className='shrink-0'
            disabled={total === 0}
            onClick={handleClearRecycle}
            hidden={!canClear}
          >
            <Trash2 className='mr-2 h-4 w-4' />
            {t('recycle.clearTrash')}
          </Button>
        </div>
      </div>

      <div className='flex flex-wrap items-center justify-between gap-x-3 gap-y-2 px-3 pt-2 pb-1.5 sm:px-6 sm:pt-2.5'>
        <div className='flex items-center gap-2'>
          {/* 与文件页一致：用文字按钮全选，选中态由行背景色表达 */}
          {fileList.length > 0 && (
            <Button
              variant='ghost'
              size='sm'
              className='text-muted-foreground hover:text-foreground'
              onClick={() => handleSelectAll(!isAllPageSelected)}
            >
              {isAllPageSelected
                ? t('index.deselectAll')
                : t('index.selectAll')}
            </Button>
          )}
          <span className='text-sm text-muted-foreground'>
            {selectedIds.length > 0
              ? t('recycle.selectedHint', { count: selectedIds.length })
              : t('recycle.totalHint', { total })}
          </span>
        </div>
      </div>

      {/* 顶部留白放这层：滚动容器内不留 pt，粘性表头才能一上来就贴住工具行 */}
      <div className='flex-1 overflow-hidden pt-1 sm:pt-1.5'>
        <div className='flex h-full min-h-0 flex-col'>
          {loading ? (
            <div className='flex h-full items-center justify-center'>
              <p className='text-muted-foreground'>{t('common.loading')}</p>
            </div>
          ) : fileList.length === 0 ? (
            <div className='flex h-full items-center justify-center'>
              <Empty className='border-none'>
                <EmptyHeader>
                  <EmptyMedia variant='icon'>
                    <FileText className='h-12 w-12' />
                  </EmptyMedia>
                  <EmptyTitle>{t('recycle.emptyTitle')}</EmptyTitle>
                  <EmptyDescription>
                    {searchKeyword
                      ? t('recycle.emptySearch')
                      : t('recycle.emptyDefault')}
                  </EmptyDescription>
                </EmptyHeader>
              </Empty>
            </div>
          ) : (
            <>
              <div className='min-h-0 flex-1 overflow-auto px-3 pb-3 sm:px-6 sm:pb-6'>
                {/* 窄屏不压缩列宽，改为横向滚动保留全部列；
                    containerClassName 必须清掉自带的 overflow-auto，否则它就成了粘性表头最近的滚动祖先 */}
                <Table
                  className='min-w-[40rem]'
                  containerClassName='overflow-visible'
                >
                  {/* sticky 加在 th 而非 thead（Firefox 不支持 table-section 级 sticky），不画表头底线 */}
                  <TableHeader className='[&_th]:sticky [&_th]:top-0 [&_th]:z-20 [&_th]:bg-background [&_tr]:border-0'>
                    {table.getHeaderGroups().map((headerGroup) => (
                      <TableRow key={headerGroup.id}>
                        {headerGroup.headers.map((header) => (
                          <TableHead
                            key={header.id}
                            className={cn(
                              'font-medium text-muted-foreground',
                              RECYCLE_TABLE_HEAD[header.column.id] ?? ''
                            )}
                          >
                            {header.isPlaceholder
                              ? null
                              : flexRender(
                                  header.column.columnDef.header,
                                  header.getContext()
                                )}
                          </TableHead>
                        ))}
                      </TableRow>
                    ))}
                  </TableHeader>
                  <TableBody>
                    {table.getRowModel().rows.length > 0 ? (
                      table.getRowModel().rows.map((row, rowIndex) => (
                        <ContextMenu key={row.id}>
                          <ContextMenuTrigger asChild>
                            <TableRow
                              className={cn(
                                'group border-b-0 transition-colors',
                                'hover:bg-primary/[0.06]',
                                selectedIds.includes(row.original.id) &&
                                  'bg-primary/[0.08]'
                              )}
                              onClick={(e) =>
                                handleRowClick(rowIndex, row.original.id, e)
                              }
                            >
                              {row.getVisibleCells().map((cell) => (
                                <TableCell
                                  key={cell.id}
                                  className={cn(
                                    // 操作列只在触屏出现：鼠标端靠右键，与文件页一致
                                    cell.column.id === 'actions' &&
                                      'hoverable:hidden'
                                  )}
                                  onClick={
                                    cell.column.id === 'actions'
                                      ? (e) => e.stopPropagation()
                                      : undefined
                                  }
                                >
                                  {flexRender(
                                    cell.column.columnDef.cell,
                                    cell.getContext()
                                  )}
                                </TableCell>
                              ))}
                            </TableRow>
                          </ContextMenuTrigger>
                          <ContextMenuContent>
                            {canRestore && (
                              <ContextMenuItem
                                onClick={() =>
                                  handleRestoreSingle(
                                    row.original.id,
                                    row.original.displayName
                                  )
                                }
                              >
                                <Undo2 className='size-4' />
                                {t('recycle.menuRestore')}
                              </ContextMenuItem>
                            )}
                            {canRestore && canDelete && (
                              <ContextMenuSeparator />
                            )}
                            {canDelete && (
                              <ContextMenuItem
                                className='text-destructive focus:text-destructive'
                                onClick={() =>
                                  handleDeleteSingle(
                                    row.original.id,
                                    row.original.displayName
                                  )
                                }
                              >
                                <Trash2 className='size-4' />
                                {t('recycle.menuDeleteForever')}
                              </ContextMenuItem>
                            )}
                          </ContextMenuContent>
                        </ContextMenu>
                      ))
                    ) : (
                      <TableRow>
                        <TableCell
                          colSpan={columns.length}
                          className='h-24 text-center'
                        >
                          {t('recycle.noData')}
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </div>
              {/* 分页组件靠名为 content 的容器查询做窄屏折叠，此处必须提供容器 */}
              <div className='@container/content shrink-0 border-t px-3 py-3 sm:px-6'>
                <DataTablePagination table={table} />
              </div>
            </>
          )}
        </div>
      </div>

      {selectedIds.length > 0 && canOperateRecycle && (
        <BulkSelectionBar
          selectedCount={selectedIds.length}
          onClear={clearSelection}
          ariaLabel={t('recycle.bulkBar')}
        >
          {canRestore && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  type='button'
                  variant='outline'
                  size='icon'
                  className='size-8 shrink-0'
                  onClick={handleBatchRestore}
                  aria-label={t('recycle.ariaRestore')}
                >
                  <Undo2 />
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                <p>{t('recycle.tooltipRestore')}</p>
              </TooltipContent>
            </Tooltip>
          )}
          {canDelete && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  type='button'
                  variant='destructive'
                  size='icon'
                  className='size-8 shrink-0'
                  onClick={handleBatchDelete}
                  aria-label={t('recycle.ariaDelete')}
                >
                  <Trash2 />
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                <p>{t('recycle.tooltipDeleteForever')}</p>
              </TooltipContent>
            </Tooltip>
          )}
        </BulkSelectionBar>
      )}

      <AlertDialog open={restoreDialogOpen} onOpenChange={setRestoreDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('recycle.confirmRestoreTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {operatingItem
                ? t('recycle.confirmRestoreOne', {
                    name: operatingItem.name,
                  })
                : t('recycle.confirmRestoreMany', {
                    count: selectedIds.length,
                  })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmRestore}>
              {t('recycle.restoreAction')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('recycle.confirmDeleteTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {operatingItem
                ? t('recycle.confirmDeleteOne', {
                    name: operatingItem.name,
                  })
                : t('recycle.confirmDeleteMany', {
                    count: selectedIds.length,
                  })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={confirmDelete}
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
            >
              {t('recycle.deleteForever')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={clearDialogOpen} onOpenChange={setClearDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('recycle.confirmEmptyTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {t('recycle.confirmEmptyDesc')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={confirmClearRecycle}
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
            >
              {t('recycle.emptyTrash')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
