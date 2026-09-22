import { useTranslation } from 'react-i18next'
import {
  Share2,
  Heart,
  Move,
  Trash2,
  Download,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { BulkSelectionBar } from '@/components/bulk-selection-bar'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { RequirePermission } from '@/components/require-permission'
import { useFeatureStore } from '@/store/feature'

interface FileBulkSelectionBarProps {
  selectedCount: number
  hasUnfavorited: boolean
  onShare: () => void
  onFavorite: () => void
  onMove: () => void
  onDelete: () => void
  onDownload: () => void
  onClear: () => void
}

export function FileBulkSelectionBar({
  selectedCount,
  hasUnfavorited,
  onShare,
  onFavorite,
  onMove,
  onDelete,
  onDownload,
  onClear,
}: FileBulkSelectionBarProps) {
  const { t } = useTranslation('files')
  // 收藏功能未开启时，隐藏收藏按钮（与侧边栏「收藏」菜单同开关）
  const favoriteEnabled = useFeatureStore((s) => !!s.toggles.favorite)
  // 分享功能未开启（或缺省）时，隐藏分享按钮
  const shareEnabled = useFeatureStore((s) => !!s.toggles.share)
  // 单选不出批量工具条：单文件操作都在右键/卡片菜单里
  if (selectedCount <= 1) return null
  return (
    <BulkSelectionBar
      selectedCount={selectedCount}
      onClear={onClear}
      ariaLabel={t('bulk.ariaBar')}
      className='bottom-14 sm:bottom-16'
    >
      <RequirePermission code='file:read'>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type='button'
              variant='outline'
              size='icon'
              className='size-8 shrink-0'
              onClick={onDownload}
              aria-label={t('bulk.ariaDownload')}
            >
              <Download />
            </Button>
          </TooltipTrigger>
          <TooltipContent>
            <p>{t('rowMenu.download')}</p>
          </TooltipContent>
        </Tooltip>
      </RequirePermission>

      {shareEnabled && (
        <RequirePermission code='file:share'>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                type='button'
                variant='outline'
                size='icon'
                className='size-8 shrink-0'
                onClick={onShare}
                aria-label={t('bulk.ariaShare')}
              >
                <Share2 />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>{t('rowMenu.share')}</p>
            </TooltipContent>
          </Tooltip>
        </RequirePermission>
      )}

      {favoriteEnabled && (
        <RequirePermission code='file:write'>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                type='button'
                variant='outline'
                size='icon'
                className='size-8 shrink-0'
                onClick={onFavorite}
                aria-label={t('bulk.ariaFavorite')}
              >
                <Heart fill={hasUnfavorited ? 'none' : 'currentColor'} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>{t('rowMenu.favorite')}</p>
            </TooltipContent>
          </Tooltip>
        </RequirePermission>
      )}

      <RequirePermission code='file:write'>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type='button'
              variant='outline'
              size='icon'
              className='size-8 shrink-0'
              onClick={onMove}
              aria-label={t('bulk.ariaMove')}
            >
              <Move />
            </Button>
          </TooltipTrigger>
          <TooltipContent>
            <p>{t('rowMenu.move')}</p>
          </TooltipContent>
        </Tooltip>
      </RequirePermission>

      <RequirePermission code='file:write'>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type='button'
              variant='destructive'
              size='icon'
              className='size-8 shrink-0'
              onClick={onDelete}
              aria-label={t('bulk.ariaTrash')}
            >
              <Trash2 />
            </Button>
          </TooltipTrigger>
          <TooltipContent>
            <p>{t('rowMenu.trash')}</p>
          </TooltipContent>
        </Tooltip>
      </RequirePermission>
    </BulkSelectionBar>
  )
}
