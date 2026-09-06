import { useTranslation } from 'react-i18next'
import {
  ChevronDown,
  FilePlus,
  FolderPlus,
  RefreshCw,
  Upload,
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
import { RequirePermission } from '@/components/require-permission'

interface ToolbarProps {
  searchKeyword: string
  onSearchChange: (keyword: string) => void
  onSearch: (keyword: string) => void
  onUpload: () => void
  onCreateFolder: () => void
  onCreateText?: () => void
  onRefresh: () => void
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
  onRefresh,
  hideActions = false,
  selectMode = false,
  onToggleSelectMode,
}: ToolbarProps) {
  const { t } = useTranslation('files')
  const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      onSearch(searchKeyword)
    }
  }

  return (
    // 窄屏时占满所在行并允许收缩，搜索框吃掉剩余宽度；宽屏恢复内容宽度靠右排列
    <div className='flex min-w-0 flex-1 items-center gap-2 sm:w-auto sm:flex-none'>
      <div className='relative min-w-0 flex-1 sm:w-64 sm:flex-none'>
        <Input
          placeholder={t('toolbar.searchPlaceholder')}
          value={searchKeyword}
          onChange={(e) => onSearchChange(e.target.value)}
          onKeyDown={handleSearchKeyDown}
        />
      </div>
      {/* 搜索与刷新合一：keyword 未变时 onRefresh 是唯一能强制重拉的途径；
          keyword 变了时 URL 变化会再触发一次 fetch，useFileList 的 generation 守卫会丢弃旧的那次 */}
      <Button
        variant='outline'
        className='shrink-0 gap-1.5'
        onClick={() => {
          onSearch(searchKeyword)
          onRefresh()
        }}
      >
        <RefreshCw className='h-4 w-4 opacity-70' />
        {t('toolbar.search')}
      </Button>
      {/* 文件操作是本页唯一的主按钮，放搜索右侧 */}
      {!hideActions && (
        <RequirePermission code='file:write'>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size='sm' className='shrink-0 gap-1.5'>
                {t('index.fileActions')}
                <ChevronDown className='h-4 w-4 opacity-70' />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align='end' className='min-w-44'>
              <DropdownMenuItem onClick={onUpload}>
                <Upload />
                {t('index.uploadFile')}
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={onCreateFolder}>
                <FolderPlus />
                {t('index.newFolder')}
              </DropdownMenuItem>
              {onCreateText && (
                <DropdownMenuItem onClick={onCreateText}>
                  <FilePlus />
                  {t('index.newTextFile')}
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </RequirePermission>
      )}
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
