import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { getPreviewToken } from '@/api/file'
import { PreviewTopBar } from './PreviewTopBar'
import type { PreviewViewerProps } from '@/utils/preview-types'
import type { FileItem } from '@/types/file'

export function EmbeddedPageViewer({ files, index }: PreviewViewerProps) {
  const file = files[index]
  if (!file) return null
  return <EmbeddedPageBody key={file.id} file={file} files={files} index={index} />
}

interface EmbeddedPageBodyProps {
  file: FileItem
  files: FileItem[]
  index: number
}

function EmbeddedPageBody({ file, files, index }: EmbeddedPageBodyProps) {
  const { t } = useTranslation('files')
  const [pageUrl, setPageUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)

  // excel/压缩包/tif/drawio：取预览 token 后内嵌后端 Thymeleaf 页面
  useEffect(() => {
    let cancelled = false
    getPreviewToken(file.id)
      .then((token) => {
        if (cancelled) return
        setPageUrl(
          `${import.meta.env.VITE_API_BASE_URL}/preview/${file.id}?previewToken=${token}`
        )
      })
      .catch(() => {
        if (cancelled) return
        setFailed(true)
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [file.id])

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName}>
        {files.length > 1 && (
          <span className='text-sm tabular-nums'>
            {index + 1} / {files.length}
          </span>
        )}
      </PreviewTopBar>
      <div className='relative min-h-0 flex-1 bg-muted'>
        {loading && !failed && (
          <div className='absolute inset-0 z-10 flex items-center justify-center'>
            <Loader2 className='h-8 w-8 animate-spin text-muted-foreground' />
          </div>
        )}
        {failed ? (
          <div className='flex h-full items-center justify-center text-muted-foreground'>
            {t('preview.embedFail')}
          </div>
        ) : (
          pageUrl && (
            <iframe
              src={pageUrl}
              title={t('preview.title')}
              className='h-full w-full'
              onLoad={() => setLoading(false)}
            />
          )
        )}
      </div>
    </div>
  )
}
