import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowRightToLine,
  ListMusic,
  Pause,
  Play,
  Repeat1,
  Shuffle,
  SkipBack,
  SkipForward,
} from 'lucide-react'
import { Slider } from '@/components/ui/slider'
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

type AudioPlayMode = 'sequential' | 'play-once' | 'repeat-one' | 'shuffle'

const PLAY_MODES: AudioPlayMode[] = ['sequential', 'play-once', 'repeat-one', 'shuffle']

export function AudioPreviewViewer({ files, index, onSwitch }: PreviewViewerProps) {
  const file = files[index]
  // 初始为 true：点开音频即自动播放（由点击打开的浏览器用户激活保证放行）
  const resumePlayback = useRef(true)
  if (!file) return null
  return (
    <AudioPreviewBody
      key={file.id}
      file={file}
      files={files}
      index={index}
      onSwitch={onSwitch}
      resumePlayback={resumePlayback}
    />
  )
}

interface AudioPreviewBodyProps {
  file: FileItem
  files: FileItem[]
  index: number
  onSwitch: (index: number) => void
  /** 包装层持有：切换曲目时是否延续播放；初始打开自动播放 */
  resumePlayback: { current: boolean }
}

function AudioPreviewBody({ file, files, index, onSwitch, resumePlayback }: AudioPreviewBodyProps) {
  const { t } = useTranslation('files')
  const audioRef = useRef<HTMLAudioElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const audioSrc = usePreviewStreamUrl(file.id)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [playMode, setPlayMode] = useState<AudioPlayMode>(() => {
    const stored = localStorage.getItem('gfs.preview.audio-play-mode')
    return stored === 'play-once' || stored === 'repeat-one' || stored === 'shuffle'
      ? stored
      : 'sequential'
  })

  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [index])

  /** 切换曲目时记录当前播放态：播放中切歌延续播放；暂停中切歌保持暂停 */
  const switchTo = (nextIndex: number) => {
    const audio = audioRef.current
    resumePlayback.current = !!audio && !audio.paused
    onSwitch(nextIndex)
  }

  const playIfResumed = () => {
    if (resumePlayback.current) {
      resumePlayback.current = false
      void audioRef.current?.play().catch(() => undefined)
    }
  }

  const cyclePlayMode = () => {
    setPlayMode((prev) => {
      const next = PLAY_MODES[(PLAY_MODES.indexOf(prev) + 1) % PLAY_MODES.length]
      localStorage.setItem('gfs.preview.audio-play-mode', next)
      return next
    })
  }

  const playModeTitle =
    playMode === 'sequential'
      ? t('preview.playSequential')
      : playMode === 'play-once'
        ? t('preview.playOnce')
        : playMode === 'repeat-one'
          ? t('preview.playRepeatOne')
          : t('preview.playShuffle')

  // 键盘控制须在 capture 阶段拦截并阻止传播，否则聚焦进度条滑块后
  // Radix 会抢先处理方向键（仅 0.1s 步进）导致快进快退失效：
  // 空格 播放/暂停；←/→ 快退快进 5s；Ctrl+←/→ 或 PgUp/PgDn 切换曲目
  useEffect(() => {
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
        const nextIndex = e.key === 'ArrowLeft' ? index - 1 : index + 1
        if (nextIndex >= 0 && nextIndex < files.length) switchTo(nextIndex)
        return
      }

      if (e.key === ' ') {
        // 聚焦在按钮上时交给原生行为（播放按钮/连播开关各自生效）
        if (target && target.tagName === 'BUTTON') return
        const audio = audioRef.current
        if (!audio) return
        e.preventDefault()
        e.stopPropagation()
        if (audio.paused) void audio.play().catch(() => undefined)
        else audio.pause()
        return
      }

      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        const audio = audioRef.current
        if (!audio || !Number.isFinite(audio.duration) || audio.duration <= 0)
          return
        e.preventDefault()
        e.stopPropagation()
        const next = Math.min(
          Math.max(0, audio.currentTime + (e.key === 'ArrowLeft' ? -5 : 5)),
          audio.duration
        )
        audio.currentTime = next
        setCurrentTime(next)
        return
      }

      if (e.key === 'PageUp' || e.key === 'PageDown') {
        e.preventDefault()
        e.stopPropagation()
        const nextIndex = e.key === 'PageUp' ? index - 1 : index + 1
        if (nextIndex >= 0 && nextIndex < files.length) switchTo(nextIndex)
        return
      }
    }

    window.addEventListener('keydown', handleKeyDown, true)
    return () => window.removeEventListener('keydown', handleKeyDown, true)
  }, [index, files.length, onSwitch, switchTo])

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

      {/* 手机竖屏上下布局（控制区在上、列表收底部），sm 起恢复左右分栏 */}
      <div className='flex min-h-0 flex-1 flex-col sm:flex-row'>
        {/* 曲目列表（对齐视频预览：列表头 = 播放列表 + 数量 + 播放模式） */}
        <div className='order-2 flex h-44 w-full shrink-0 flex-col border-t bg-card sm:order-1 sm:h-auto sm:w-72 sm:border-r sm:border-t-0'>
          <div className='flex items-center justify-between gap-2 border-b px-3 py-2.5'>
            <span className='text-sm text-foreground'>
              {t('preview.playlist')}
              <span className='ml-2 text-xs text-muted-foreground tabular-nums'>
                {files.length}
              </span>
            </span>
            <div className='flex items-center gap-1.5'>
              <span className='text-xs text-muted-foreground'>
                {t('preview.playMode')}
              </span>
              <Button
                variant='ghost'
                size='icon'
                className='h-7 w-7 text-muted-foreground hover:text-foreground'
                title={playModeTitle}
                onClick={cyclePlayMode}
              >
                {playMode === 'sequential' && <ListMusic className='h-4 w-4' />}
                {playMode === 'play-once' && <ArrowRightToLine className='h-4 w-4' />}
                {playMode === 'repeat-one' && <Repeat1 className='h-4 w-4' />}
                {playMode === 'shuffle' && <Shuffle className='h-4 w-4' />}
              </Button>
            </div>
          </div>
          <ul
            ref={listRef}
            className='min-h-0 flex-1 space-y-0.5 overflow-y-auto p-2'
          >
            {files.map((item, i) => (
              <li
                key={item.id}
                data-active={i === index}
                title={item.displayName}
                onClick={() => switchTo(i)}
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
        </div>

        {/* 主控区 */}
        <div className='order-1 flex min-w-0 flex-1 flex-col items-center justify-center gap-4 bg-background px-4 text-foreground sm:order-2 sm:gap-8 sm:px-8'>
          {audioSrc && (
            <audio
              ref={audioRef}
              src={audioSrc}
              loop={playMode === 'repeat-one'}
              onCanPlay={playIfResumed}
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onTimeUpdate={(e) => setCurrentTime(e.currentTarget.currentTime)}
              onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
              onEnded={() => {
                const audio = audioRef.current
                if (playMode === 'repeat-one') {
                  // loop 属性正常已接管，此处兜底
                  if (audio) {
                    audio.currentTime = 0
                    void audio.play().catch(() => undefined)
                  }
                  return
                }
                if (playMode === 'shuffle' && files.length > 1) {
                  let next = index
                  while (next === index) {
                    next = Math.floor(Math.random() * files.length)
                  }
                  // switchTo 会按已结束的暂停态覆盖延续标志，随机切歌须直接置真
                  resumePlayback.current = true
                  onSwitch(next)
                  return
                }
                if (playMode === 'sequential' && index < files.length - 1) {
                  resumePlayback.current = true
                  onSwitch(index + 1)
                  return
                }
                if (playMode === 'shuffle' && audio) {
                  // 随机但仅一首：重播
                  audio.currentTime = 0
                  void audio.play().catch(() => undefined)
                  return
                }
                setPlaying(false)
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
              title={`${t('preview.prev')} (Ctrl+← / PgUp)`}
              onClick={() => switchTo(index - 1)}
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
              title={`${t('preview.next')} (Ctrl+→ / PgDn)`}
              onClick={() => switchTo(index + 1)}
            >
              <SkipForward className='h-6 w-6' />
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
