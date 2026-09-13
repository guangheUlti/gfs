import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, VideoOff } from 'lucide-react'
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
  const videoRef = useRef<HTMLVideoElement>(null)
  const frameProbeTimer = useRef<number | undefined>(undefined)
  // loading=取流/解码中 ready=已出画面 error=整段不可播 noPicture=视频轨解不出但声音正常（如 HEVC）
  const [status, setStatus] = useState<'loading' | 'ready' | 'error' | 'noPicture'>('loading')

  useEffect(() => {
    setStatus('loading')
    window.clearTimeout(frameProbeTimer.current)
  }, [file.id])

  useEffect(() => () => window.clearTimeout(frameProbeTimer.current), [])

  /**
   * HEVC 等视频轨解不出时浏览器不触发 onError（音频轨正常就继续"播放"），
   * 只能在开始播放后检查解码帧数：播放中超过宽限期仍 0 帧 → 判定画面不可解
   */
  const probeDecodedFrames = () => {
    window.clearTimeout(frameProbeTimer.current)
    frameProbeTimer.current = window.setTimeout(() => {
      const v = videoRef.current
      if (!v || v.paused || v.readyState < 2) return
      const frames = v.getVideoPlaybackQuality?.().totalVideoFrames ?? 0
      if (frames === 0) setStatus('noPicture')
    }, 1500)
  }

  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [index])

  // 键盘控制与音频预览一致，capture 阶段拦截并阻止传播：
  // 空格 播放/暂停；←/→ 快退快进 5s；Ctrl+←/→ 或 PgUp/PgDn 切换视频
  useEffect(() => {
    const switchTo = (nextIndex: number) => {
      if (nextIndex >= 0 && nextIndex < files.length) onSwitch(nextIndex)
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return

      const target = e.target as HTMLElement | null
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.isContentEditable)
      ) {
        return
      }

      // Ctrl 分支必须先于普通方向键判断（Ctrl+← 的 key 也是 ArrowLeft）
      if (e.ctrlKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault()
        e.stopPropagation()
        switchTo(e.key === 'ArrowLeft' ? index - 1 : index + 1)
        return
      }

      if (e.key === ' ') {
        // 聚焦在按钮上时交给原生行为（自动连播开关等各自生效）
        if (target && target.tagName === 'BUTTON') return
        const video = videoRef.current
        if (!video) return
        e.preventDefault()
        e.stopPropagation()
        if (video.paused) void video.play().catch(() => undefined)
        else video.pause()
        return
      }

      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        const video = videoRef.current
        if (!video || !Number.isFinite(video.duration) || video.duration <= 0)
          return
        e.preventDefault()
        e.stopPropagation()
        video.currentTime = Math.min(
          Math.max(0, video.currentTime + (e.key === 'ArrowLeft' ? -5 : 5)),
          video.duration
        )
        return
      }

      if (e.key === 'PageUp' || e.key === 'PageDown') {
        e.preventDefault()
        e.stopPropagation()
        switchTo(e.key === 'PageUp' ? index - 1 : index + 1)
      }
    }

    window.addEventListener('keydown', handleKeyDown, true)
    return () => window.removeEventListener('keydown', handleKeyDown, true)
  }, [index, files.length, onSwitch])

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName}>
        <span className='text-sm tabular-nums'>
          {index + 1} / {files.length}
        </span>
      </PreviewTopBar>

      {/* 播放区铺满下方整个区域，播放框固定 16:9、80% 宽居中；背景跟随主题色，不再用纯黑 */}
      <div className='flex min-h-0 flex-1'>
        <div className='flex min-w-0 flex-1 items-center justify-center bg-muted/40'>
          <div className='relative aspect-video max-h-full w-[80%]'>
            {videoSrc && (
              <video
                key={file.id}
                ref={videoRef}
                src={videoSrc}
                controls
                autoPlay
                className='h-full w-full rounded-lg object-contain'
                onLoadedData={() => setStatus('ready')}
                onError={() => setStatus('error')}
                onPlaying={probeDecodedFrames}
                onEnded={() => {
                  if (autoNext && index < files.length - 1) onSwitch(index + 1)
                }}
              />
            )}
            {status === 'loading' && videoSrc && (
              <div className='absolute inset-0 flex flex-col items-center justify-center gap-3 text-muted-foreground'>
                <Loader2 className='h-8 w-8 animate-spin' />
                <span className='text-sm'>{t('preview.videoLoading')}</span>
              </div>
            )}
            {/* 提示在播放框正中央：控制条贴框底，与居中提示互不遮挡 */}
            {status === 'noPicture' && (
              <div className='pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-2 px-6 text-center'>
                <VideoOff className='h-10 w-10 shrink-0 text-muted-foreground' />
                <p className='max-w-md text-sm text-foreground'>
                  {t('preview.videoFrameFail')}
                </p>
                <p className='max-w-md text-xs text-muted-foreground'>
                  {t('preview.videoLoadFailHint')}
                </p>
              </div>
            )}
            {status === 'error' && (
              <div className='absolute inset-0 flex flex-col items-center justify-center gap-2 px-6 text-center'>
                <VideoOff className='h-10 w-10 shrink-0 text-muted-foreground' />
                <p className='max-w-md text-sm text-foreground'>
                  {t('preview.videoLoadFail')}
                </p>
                <p className='max-w-md text-xs text-muted-foreground'>
                  {t('preview.videoLoadFailHint')}
                </p>
              </div>
            )}
          </div>
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
