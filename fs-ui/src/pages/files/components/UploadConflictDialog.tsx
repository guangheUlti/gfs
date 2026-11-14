import { useTranslation } from 'react-i18next'
import type { FileConflictVO } from '@/types/transfer'
import { formatFileSize } from '@/utils/format'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

interface UploadConflictDialogProps {
  open: boolean
  conflicts: FileConflictVO[]
  onReplace: () => void
  onKeepBoth: () => void
  onOpenChange: (open: boolean) => void
}

/**
 * 上传同名冲突：与 Windows 资源管理器一致，让用户选择覆盖还是保留两者。
 * 关闭弹窗（Esc / 点遮罩）等同取消本次上传，避免默认某个策略后误覆盖。
 */
export function UploadConflictDialog({
  open,
  conflicts,
  onReplace,
  onKeepBoth,
  onOpenChange,
}: UploadConflictDialogProps) {
  const { t } = useTranslation('files')

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-w-md'>
        <DialogHeader>
          <DialogTitle>{t('conflictDialog.title')}</DialogTitle>
          <DialogDescription>
            {t('conflictDialog.desc', { count: conflicts.length })}
          </DialogDescription>
        </DialogHeader>

        <div className='max-h-56 overflow-y-auto rounded-lg border border-border'>
          {conflicts.map((conflict) => (
            <div
              key={conflict.fileId}
              className='flex items-center justify-between gap-3 border-b border-border px-3 py-2 last:border-b-0'
            >
              <span
                className='min-w-0 truncate text-sm'
                title={conflict.fileName}
              >
                {conflict.fileName}
              </span>
              <span className='shrink-0 text-xs text-muted-foreground'>
                {conflict.isDir
                  ? t('conflictDialog.folder')
                  : formatFileSize(conflict.size ?? 0)}
              </span>
            </div>
          ))}
        </div>

        <DialogFooter>
          <Button variant='outline' onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button variant='outline' onClick={onKeepBoth}>
            {t('conflictDialog.keepBoth')}
          </Button>
          <Button variant='destructive' onClick={onReplace}>
            {t('conflictDialog.replace')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
