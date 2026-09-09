import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

// 弹窗标题里的类型展示名：md 用全称，其余大写
const TYPE_LABELS: Record<string, string> = { txt: 'TXT', json: 'JSON', md: 'Markdown' }

interface CreateTextModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 新建文件的固定后缀（txt/json/md），由入口菜单决定 */
  suffix: string
  parentId?: string
  onConfirm: (fileName: string, parentId?: string) => void
}

export function CreateTextModal({
  open,
  onOpenChange,
  suffix,
  parentId,
  onConfirm,
}: CreateTextModalProps) {
  const { t } = useTranslation('files')
  const [fileName, setFileName] = useState('')

  useEffect(() => {
    if (!open) {
      setFileName('')
    }
  }, [open])

  const handleConfirm = () => {
    const name = fileName.trim()
    if (!name) return
    // 只传主体名，后缀由后端统一拼接
    onConfirm(name, parentId)
    onOpenChange(false)
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleConfirm()
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='sm:max-w-[420px]'>
        <DialogHeader>
          <DialogTitle>
            {t('createText.title', { type: TYPE_LABELS[suffix] ?? suffix.toUpperCase() })}
          </DialogTitle>
        </DialogHeader>
        <div className='space-y-6'>
          <div className='flex items-center justify-center'>
            <FileText className='h-16 w-16 text-primary/70' />
          </div>
          {/* 输入主体名，右侧固定显示后缀 */}
          <div className='relative'>
            <Input
              placeholder={t('createText.placeholder')}
              value={fileName}
              onChange={(e) => setFileName(e.target.value)}
              onKeyDown={handleKeyDown}
              autoFocus
              maxLength={50}
              className='pr-14'
            />
            <span className='pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm text-muted-foreground'>
              .{suffix}
            </span>
          </div>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={() => onOpenChange(false)}>
            {t('createText.cancel')}
          </Button>
          <Button onClick={handleConfirm} disabled={!fileName.trim()}>
            {t('createText.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
