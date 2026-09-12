import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import { PreviewTopBar } from './PreviewTopBar'
import { usePreviewStreamUrl, type PreviewViewerProps } from '@/utils/preview-types'

export function VideoPreviewViewer({ files, index, onSwitch }: PreviewViewerProps) {
  const { t } = useTranslation('files')
  const file = files[index]
  const videoSrc = usePreviewStreamUrl(file.id)
  const [autoNext, setAutoNext] = useState(
    () => localStorage.getItem('gfs.preview.video-auto-next') === 'true'
  )
  const listRef = useRef<HTMLUListElement>(null)

  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [index])

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName}>
        <span className='text-sm tabular-nums'>
          {index + 1} / {files.length}
        </span>
      </PreviewTopBar>

      <div className='flex min-h-0 flex-1'>
        <div className='flex min-w-0 flex-1 items-center justify-center bg-black'>
          {videoSrc && (
            <video
              key={file.id}
              src={videoSrc}
              controls
              autoPlay
              className='max-h-full max-w-full'
              onEnded={() => {
                if (autoNext && index < files.length - 1) onSwitch(index + 1)
              }}
            />
          )}
        </div>

        <div className='flex w-72 shrink-0 flex-col border-l bg-card'>
          <div className='flex items-center justify-between gap-2 border-b px-3 py-2.5'>
            <span className='text-sm text-foreground'>
              {t('preview.playlist')}
              <span className='ml-2 text-xs text-muted-foreground tabular-nums'>
                {files.length}
              </span>
            </span>
            <div className='flex items-center gap-1.5'>
              <Label
                htmlFor='video-auto-next'
                className='cursor-pointer text-xs text-muted-foreground'
              >
                {t('preview.autoNext')}
              </Label>
              <Switch
                id='video-auto-next'
                checked={autoNext}
                onCheckedChange={(checked) => {
                  setAutoNext(checked)
                  localStorage.setItem('gfs.preview.video-auto-next', String(checked))
                }}
              />
            </div>
          </div>
          <ul ref={listRef} className='min-h-0 flex-1 overflow-y-auto p-2'>
            {files.map((item, i) => (
              <li
                key={item.id}
                data-active={i === index}
                title={item.displayName}
                onClick={() => onSwitch(i)}
                className={`flex cursor-pointer items-center gap-2 rounded px-2 py-2 text-sm ${
                  i === index
                    ? 'bg-accent text-accent-foreground'
                    : 'hover:bg-accent/40'
                }`}
              >
                <span className='w-5 shrink-0 text-center text-xs tabular-nums'>
                  {i === index ? '▶' : i + 1}
                </span>
                <span className='min-w-0 flex-1 truncate'>{item.displayName}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  )
}
