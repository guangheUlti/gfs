import { useTranslation } from 'react-i18next'
import { PreviewTopBar } from './PreviewTopBar'
import { usePreviewStreamUrl, type PreviewViewerProps } from '@/utils/preview-types'

export function PdfPreviewViewer({ files, index }: PreviewViewerProps) {
  const { t } = useTranslation('files')
  const file = files[index]
  const src = usePreviewStreamUrl(file.id)
  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName}>
        {files.length > 1 && (
          <span className='text-sm tabular-nums'>
            {index + 1} / {files.length}
          </span>
        )}
      </PreviewTopBar>
      {src && (
        <iframe
          src={src}
          title={t('preview.title')}
          className='min-h-0 flex-1 bg-background'
        />
      )}
    </div>
  )
}
