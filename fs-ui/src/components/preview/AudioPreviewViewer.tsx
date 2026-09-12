import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pause, Play, SkipBack, SkipForward } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { formatFileSize } from '@/utils/format'
import { PreviewTopBar } from './PreviewTopBar'
import { usePreviewStreamUrl, type PreviewViewerProps } from '@/utils/preview-types'
import type { FileItem } from '@/types/file'

function fmtTime(seconds: number): string {
  if (!Number.isFinite(seconds)) return '0:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${String(s).padStart(2, '0')}`
}

export function AudioPreviewViewer({ files, index, onSwitch }: PreviewViewerProps) {
  const file = files[index]
  if (!file) return null
  return (
    <AudioPreviewBody
      key={file.id}
      file={file}
      files={files}
      index={index}
      onSwitch={onSwitch}
    />
  )
}

interface AudioPreviewBodyProps {
  file: FileItem
  files: FileItem[]
  index: number
  onSwitch: (index: number) => void
}

function AudioPreviewBody({ file, files, index, onSwitch }: AudioPreviewBodyProps) {
  const { t } = useTranslation('files')
  const audioRef = useRef<HTMLAudioElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const audioSrc = usePreviewStreamUrl(file.id)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [autoNext, setAutoNext] = useState(
    () => localStorage.getItem('gfs.preview.audio-auto-next') !== 'false'
  )

  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [index])

  const togglePlay = () => {
    const audio = audioRef.current
    if (!audio) return
    if (audio.paused) {
      audio.play()
    } else {
      audio.pause()
    }
  }

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName}>
        <span className='text-sm tabular-nums'>
          {index + 1} / {files.length}
        </span>
      </PreviewTopBar>

      <div className='flex min-h-0 flex-1'>
        {/* 曲目列表（ffs audio-list：名称 + 大小） */}
        <ul
          ref={listRef}
          className='w-[260px] shrink-0 space-y-0.5 overflow-y-auto border-r bg-card p-2'
        >
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
                {i === index ? (playing ? '♪' : '❚❚') : i + 1}
              </span>
              <span className='min-w-0 flex-1 truncate'>{item.displayName}</span>
              <span className='shrink-0 text-xs text-muted-foreground'>
                {formatFileSize(item.size)}
              </span>
            </li>
          ))}
        </ul>

        {/* 主控区 */}
        <div className='flex min-w-0 flex-1 flex-col items-center justify-center gap-8 bg-background px-8 text-foreground'>
          {audioSrc && (
            <audio
              ref={audioRef}
              src={audioSrc}
              autoPlay
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onTimeUpdate={(e) => setCurrentTime(e.currentTarget.currentTime)}
              onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
              onEnded={() => {
                if (autoNext && index < files.length - 1) onSwitch(index + 1)
                else setPlaying(false)
              }}
            />
          )}

          <div className='w-full max-w-xl text-center'>
            <p className='truncate text-xl font-medium' title={file.displayName}>
              {file.displayName}
            </p>
            <p className='mt-1 text-sm text-muted-foreground'>
              {formatFileSize(file.size)}
            </p>
          </div>

          <div className='w-full max-w-xl space-y-2'>
            <Slider
              value={[Math.min(currentTime, duration || 0)]}
              max={duration || 1}
              step={0.1}
              onValueChange={([v]) => {
                setCurrentTime(v)
                if (audioRef.current) audioRef.current.currentTime = v
              }}
            />
            <div className='flex justify-between text-xs text-muted-foreground tabular-nums'>
              <span>{fmtTime(currentTime)}</span>
              <span>{fmtTime(duration)}</span>
            </div>
          </div>

          <div className='flex items-center gap-6'>
            <Button
              variant='ghost'
              size='icon'
              className='h-11 w-11 hover:bg-accent hover:text-accent-foreground disabled:opacity-30'
              disabled={index === 0}
              title={t('preview.prev')}
              onClick={() => onSwitch(index - 1)}
            >
              <SkipBack className='h-6 w-6' />
            </Button>
            <Button
              size='icon'
              className='h-14 w-14 rounded-full'
              title={playing ? t('preview.pause') : t('preview.play')}
              onClick={togglePlay}
            >
              {playing ? (
                <Pause className='h-7 w-7' />
              ) : (
                <Play className='h-7 w-7 translate-x-0.5' />
              )}
            </Button>
            <Button
              variant='ghost'
              size='icon'
              className='h-11 w-11 hover:bg-accent hover:text-accent-foreground disabled:opacity-30'
              disabled={index === files.length - 1}
              title={t('preview.next')}
              onClick={() => onSwitch(index + 1)}
            >
              <SkipForward className='h-6 w-6' />
            </Button>
          </div>

          <div className='flex items-center gap-2'>
            <Label
              htmlFor='audio-auto-next'
              className='cursor-pointer text-sm text-muted-foreground'
            >
              {t('preview.autoNext')}
            </Label>
            <Switch
              id='audio-auto-next'
              checked={autoNext}
              onCheckedChange={(checked) => {
                setAutoNext(checked)
                localStorage.setItem('gfs.preview.audio-auto-next', String(checked))
              }}
            />
          </div>
        </div>
      </div>
    </div>
  )
}
