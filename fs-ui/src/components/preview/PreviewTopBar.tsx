import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { usePreviewStore } from '@/store/preview'

interface PreviewTopBarProps {
  title: string
  /** 未保存标记（代码/Markdown 编辑中） */
  dirty?: boolean
  /** 标题左侧工具（如缩略图列表开关） */
  leading?: ReactNode
  /** 右侧工具区（序号、旋转、保存等，随 viewer 而定） */
  children?: ReactNode
}

export function PreviewTopBar({ title, dirty, leading, children }: PreviewTopBarProps) {
  const { t } = useTranslation('files')
  const closePreview = usePreviewStore((state) => state.closePreview)
  return (
    <div className='flex h-12 shrink-0 items-center gap-4 border-b bg-background px-4 text-foreground'>
      {leading}
      <span className='min-w-0 flex-1 truncate text-base' title={title}>
        {title}
        {dirty && (
          <span className='ml-1 text-sm font-normal text-muted-foreground'>
            {t('preview.unsaved')}
          </span>
        )}
      </span>
      <div className='flex shrink-0 items-center gap-4'>{children}</div>
      <button
        type='button'
        title={t('preview.close')}
        onClick={closePreview}
        className='shrink-0 cursor-pointer opacity-90 transition-opacity hover:opacity-60'
      >
        <X className='h-5 w-5' />
      </button>
    </div>
  )
}
