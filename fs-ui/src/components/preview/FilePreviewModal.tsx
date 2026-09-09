import { Suspense, useCallback, useEffect, useMemo, lazy } from 'react'
import { useTranslation } from 'react-i18next'
import { ImageOff, Loader2 } from 'lucide-react'
import { usePreviewStore } from '@/store/preview'
import { filterSameKind, getPreviewKind } from '@/utils/preview-types'
import { ImagePreviewViewer } from './ImagePreviewViewer'
import { VideoPreviewViewer } from './VideoPreviewViewer'
import { AudioPreviewViewer } from './AudioPreviewViewer'
import { PdfPreviewViewer } from './PdfPreviewViewer'
import { EmbeddedPageViewer } from './EmbeddedPageViewer'
import { PreviewTopBar } from './PreviewTopBar'

// CodeMirror/Markdown 渲染栈体积大，独立 chunk 懒加载
const CodePreviewViewer = lazy(() =>
  import('./CodePreviewViewer').then((m) => ({ default: m.CodePreviewViewer }))
)
const MarkdownPreviewViewer = lazy(() =>
  import('./MarkdownPreviewViewer').then((m) => ({ default: m.MarkdownPreviewViewer }))
)

export function FilePreviewModal() {
  const { t } = useTranslation('files')
  const previewState = usePreviewStore((state) => state.previewState)
  const closePreview = usePreviewStore((state) => state.closePreview)
  const switchPreview = usePreviewStore((state) => state.switchPreview)

  const file = previewState?.files[previewState.index] ?? null
  const kind = useMemo(
    () => getPreviewKind(file?.suffix),
    [file?.suffix]
  )
  const kindFiles = useMemo(
    () => (previewState ? filterSameKind(previewState.files, kind) : []),
    [previewState, kind]
  )
  const kindIndex = file ? Math.max(kindFiles.findIndex((f) => f.id === file.id), 0) : 0

  const switchKindIndex = useCallback(
    (nextKindIndex: number) => {
      const target = kindFiles[nextKindIndex]
      if (!target || !previewState) return
      const rawIndex = previewState.files.findIndex((f) => f.id === target.id)
      if (rawIndex >= 0) switchPreview(rawIndex)
    },
    [kindFiles, previewState, switchPreview]
  )

  // Esc 关闭；←/→ 在同类型文件集合内切换（输入框/编辑器内不劫持按键）
  useEffect(() => {
    if (!previewState) return
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      const inEditor = !!target?.closest?.(
        'input, textarea, [contenteditable="true"], .cm-editor'
      )
      if (e.key === 'Escape') {
        e.preventDefault()
        closePreview()
        return
      }
      if (inEditor) return
      if (e.key === 'ArrowLeft' && kindIndex > 0) {
        e.preventDefault()
        switchKindIndex(kindIndex - 1)
      } else if (e.key === 'ArrowRight' && kindIndex < kindFiles.length - 1) {
        e.preventDefault()
        switchKindIndex(kindIndex + 1)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [previewState, closePreview, switchKindIndex, kindIndex, kindFiles.length])

  // 预览期间锁定页面滚动
  const hasPreview = previewState !== null
  useEffect(() => {
    if (!hasPreview) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [hasPreview])

  if (!previewState || !file) return null

  const viewerProps = {
    files: kindFiles,
    index: kindIndex,
    onSwitch: switchKindIndex,
    startEdit: previewState.edit === true,
  }

  return (
    <div className='fixed inset-0 z-[9999] flex flex-col bg-background'>
      {kind === 'image' && <ImagePreviewViewer {...viewerProps} />}
      {kind === 'video' && <VideoPreviewViewer {...viewerProps} />}
      {kind === 'audio' && <AudioPreviewViewer {...viewerProps} />}
      {kind === 'pdf' && <PdfPreviewViewer {...viewerProps} />}
      {kind === 'code' && (
        <Suspense
          fallback={
            <div className='flex flex-1 items-center justify-center text-muted-foreground'>
              <Loader2 className='h-8 w-8 animate-spin' />
            </div>
          }
        >
          <CodePreviewViewer {...viewerProps} />
        </Suspense>
      )}
      {kind === 'markdown' && (
        <Suspense
          fallback={
            <div className='flex flex-1 items-center justify-center text-muted-foreground'>
              <Loader2 className='h-8 w-8 animate-spin' />
            </div>
          }
        >
          <MarkdownPreviewViewer {...viewerProps} />
        </Suspense>
      )}
      {kind === 'embedded' && <EmbeddedPageViewer {...viewerProps} />}
      {kind === 'unsupported' && (
        <div
          className='flex flex-1 flex-col'
          onClick={(e) => {
            if (e.target === e.currentTarget) closePreview()
          }}
        >
          <PreviewTopBar title={file.displayName} />
          <div className='flex flex-1 flex-col items-center justify-center gap-3 text-muted-foreground'>
            <ImageOff className='h-10 w-10' />
            <span>{t('preview.unsupported')}</span>
          </div>
        </div>
      )}
    </div>
  )
}
