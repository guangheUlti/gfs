import { useTranslation } from 'react-i18next'
import {
  ChevronDown,
  FileJson,
  FilePlus,
  FileText,
  FolderPlus,
  Search,
  Upload,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { usePermission } from '@/hooks/use-permission'

interface ToolbarProps {
  searchKeyword: string
  onSearchChange: (keyword: string) => void
  onSearch: (keyword: string) => void
  onUpload: () => void
  onCreateFolder: () => void
  /** 新建文本：suffix 为 txt/json/md，由菜单项决定 */
  onCreateText?: (suffix: 'txt' | 'json' | 'md') => void
  hideActions?: boolean
  /** 触屏多选模式；仅当传入 onToggleSelectMode 时才渲染「选择/完成」按钮 */
  selectMode?: boolean
  onToggleSelectMode?: () => void
}

export function Toolbar({
  searchKeyword,
  onSearchChange,
  onSearch,
  onUpload,
  onCreateFolder,
  onCreateText,
  hideActions = false,
  selectMode = false,
  onToggleSelectMode,
}: ToolbarProps) {
  const { t } = useTranslation('files')
  const { hasPermission } = usePermission()
  const canWrite = hasPermission('file:write')
  const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      onSearch(searchKeyword)
    }
  }

  return (
    /*
      窄屏只保留「输入框 + 新建菜单 + 选择」：独立的「搜索」按钮在手机上隐藏（回车/清空即可搜），
      否则 312px 的手机上输入框只剩 ~64px，几乎无法输入；宽屏恢复完整分裂按钮。
    */
    <div className='flex min-w-0 flex-1 flex-wrap items-center gap-2 sm:w-auto sm:flex-none sm:flex-nowrap'>
      {/* 回车即搜（清空后回车恢复全量）；输入非空时右侧显示清空按钮，点击恢复全量列表 */}
      {/* 手机端给输入框留最小可输入宽度：容不下时「选择/更多」整体换到第二行，而不是把输入框压成几十像素 */}
      <div className='relative min-w-[7.5rem] flex-1 sm:w-44 sm:min-w-0 sm:flex-none'>
        <Input
          placeholder={t('toolbar.searchPlaceholder')}
          value={searchKeyword}
          onChange={(e) => onSearchChange(e.target.value)}
          onKeyDown={handleSearchKeyDown}
          className={searchKeyword ? 'pr-8' : undefined}
        />
        {searchKeyword && (
          <button
            type='button'
            aria-label={t('toolbar.clearSearch')}
            title={t('toolbar.clearSearch')}
            onClick={() => {
              onSearchChange('')
              onSearch('')
            }}
            className='absolute end-1.5 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground transition-colors hover:text-foreground'
          >
            <X className='h-4 w-4' />
          </button>
        )}
      </div>
      {/* 分裂按钮：左半搜索（配合左侧搜索栏，空关键词等效刷新）；右半展开新建/上传菜单（写操作） */}
      {!hideActions ? (
        <div className='inline-flex shrink-0 items-center'>
          <Button
            variant='outline'
            size='sm'
            className={
              canWrite
                ? 'hidden gap-1.5 rounded-r-none border-r-0 sm:inline-flex'
                : 'hidden gap-1.5 sm:inline-flex'
            }
            onClick={() => onSearch(searchKeyword)}
          >
            <Search className='h-4 w-4' />
            {t('toolbar.search')}
          </Button>
          {canWrite && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant='outline'
                  size='sm'
                  /* 手机端左侧没有「搜索」半截，圆角要收回来，否则是个缺一角的按钮 */
                  className='rounded-md px-1.5 sm:rounded-l-none'
                  aria-label={t('index.fileActions')}
                >
                  <ChevronDown className='h-4 w-4 opacity-70' />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align='end' className='min-w-44'>
                <DropdownMenuItem onClick={onCreateFolder}>
                  <FolderPlus />
                  {t('index.newFolder')}
                </DropdownMenuItem>
                {onCreateText && (
                  <>
                    <DropdownMenuItem onClick={() => onCreateText('txt')}>
                      <FilePlus />
                      {t('index.newTextFile')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => onCreateText('json')}>
                      <FileJson />
                      {t('index.newTextJson')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => onCreateText('md')}>
                      <FileText />
                      {t('index.newTextMd')}
                    </DropdownMenuItem>
                  </>
                )}
                <DropdownMenuSeparator />
                {/* 上传降为菜单动作，放在最底下 */}
                <DropdownMenuItem onClick={onUpload}>
                  <Upload />
                  {t('index.uploadFile')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      ) : null}
      {/* 触屏没有 Ctrl/右键，靠这个按钮进出多选模式；鼠标端隐藏 */}
      {onToggleSelectMode && (
        <Button
          variant={selectMode ? 'default' : 'outline'}
          size='sm'
          /* 手机上它常在第二行末尾；不与搜索框抢宽度 */
          className='ms-auto shrink-0 hoverable:hidden'
          onClick={onToggleSelectMode}
        >
          {selectMode ? t('index.done') : t('index.select')}
        </Button>
      )}
    </div>
  )
}
