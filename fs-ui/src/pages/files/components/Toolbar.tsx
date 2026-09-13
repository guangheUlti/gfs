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
    // 窄屏时占满所在行并允许收缩，搜索框吃掉剩余宽度；宽屏恢复内容宽度靠右排列
    <div className='flex min-w-0 flex-1 items-center gap-2 sm:w-auto sm:flex-none'>
      {/* 回车即搜（清空后回车恢复全量）；输入非空时右侧显示清空按钮，点击恢复全量列表 */}
      <div className='relative min-w-0 flex-1 sm:w-44 sm:flex-none'>
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
              canWrite ? 'gap-1.5 rounded-r-none border-r-0' : 'gap-1.5'
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
                  className='rounded-l-none px-1.5'
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
          className='shrink-0 hoverable:hidden'
          onClick={onToggleSelectMode}
        >
          {selectMode ? t('index.done') : t('index.select')}
        </Button>
      )}
    </div>
  )
}
