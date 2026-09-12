import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ChevronLeft,
  ChevronRight,
  PanelLeftClose,
  PanelLeftOpen,
  RotateCw,
} from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { PreviewTopBar } from './PreviewTopBar'
import { usePreviewStreamUrl, type PreviewViewerProps } from '@/utils/preview-types'
import type { FileItem } from '@/types/file'

const LIST_COLLAPSED_KEY = 'gfs.preview.img-list-collapsed'

export function ImagePreviewViewer({ files, index, onSwitch }: PreviewViewerProps) {
  const file = files[index]
  if (!file) return null
  return (
    <ImagePreviewBody
      key={file.id}
      file={file}
      files={files}
      index={index}
      onSwitch={onSwitch}
    />
  )
}

interface ImagePreviewBodyProps {
  file: FileItem
  files: FileItem[]
  index: number
  onSwitch: (index: number) => void
}

function ImagePreviewBody({ file, files, index, onSwitch }: ImagePreviewBodyProps) {
  const { t } = useTranslation('files')
  const mainSrc = usePreviewStreamUrl(file.id)
  const [zoom, setZoom] = useState(100)
  const [rotate, setRotate] = useState(0)
  // 默认收起缩略图列表；用户展开过则记住（localStorage 'false' = 展开）。
  // 切换图片时组件按 file.id 重挂载，偏好靠这里跨图保持。
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem(LIST_COLLAPSED_KEY) !== 'false'
  )
  const areaRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  // 默认缩放：宽、高都不超过「屏幕 60%」，四周留白；且不超过可视区（不裁剪）。
  // 基准是已等比收纳后的布局尺寸；原先按原始尺寸计算会叠加收纳缩小，导致大图过小。
  const fitZoom = useCallback((img: HTMLImageElement) => {
    const layoutW = img.clientWidth || 1
    const layoutH = img.clientHeight || 1
    const areaW = areaRef.current?.clientWidth || layoutW
    const areaH = areaRef.current?.clientHeight || layoutH
    const target = Math.min(
      (window.innerWidth * 0.6 * 100) / layoutW,
      (window.innerHeight * 0.6 * 100) / layoutH
    )
    const ceiling = Math.min((areaW / layoutW) * 100, (areaH / layoutH) * 100)
    const fit = Math.min(target, ceiling)
    setZoom(Math.min(Math.max(Math.floor(fit), 1), 200))
  }, [])

  // 滚轮缩放（非 passive 监听才能 preventDefault）
  useEffect(() => {
    const el = areaRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      setZoom((z) => Math.min(200, Math.max(1, z + (e.deltaY < 0 ? 6 : -6))))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  // 缩略图列表：当前项滚动到可视区
  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [index, collapsed])

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      localStorage.setItem(LIST_COLLAPSED_KEY, String(!prev))
      return !prev
    })
  }

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar
        title={file.displayName}
        leading={
          <button
            type='button'
            title={collapsed ? t('preview.expandList') : t('preview.collapseList')}
            onClick={toggleCollapsed}
            className='cursor-pointer opacity-90 transition-opacity hover:opacity-60'
          >
            {collapsed ? (
              <PanelLeftOpen className='h-5 w-5' />
            ) : (
              <PanelLeftClose className='h-5 w-5' />
            )}
          </button>
        }
      >
        <span className='text-sm tabular-nums'>
          {index + 1} / {files.length}
        </span>
        <button
          type='button'
          title={t('preview.rotate')}
          onClick={() => setRotate((r) => r + 90)}
          className='cursor-pointer opacity-90 transition-opacity hover:opacity-60'
        >
          <RotateCw className='h-5 w-5' />
        </button>
      </PreviewTopBar>

      <div className='flex min-h-0 flex-1'>
        {!collapsed && (
          <ul
            ref={listRef}
            className='w-[200px] shrink-0 space-y-1 overflow-y-auto bg-card p-2'
          >
            {files.map((item, i) => (
              <li
                key={item.id}
                data-active={i === index}
                title={item.displayName}
                onClick={() => onSwitch(i)}
                className={`cursor-pointer rounded p-1.5 ${
                  i === index ? 'bg-accent text-accent-foreground' : 'hover:bg-accent/40'
                }`}
              >
                <PreviewThumbImage
                  fileId={item.id}
                  className='mx-auto max-h-28 max-w-full rounded object-contain'
                />
                <p className='mt-1 truncate text-center text-xs'>
                  {item.displayName}
                </p>
              </li>
            ))}
          </ul>
        )}

        <div
          ref={areaRef}
          className='relative flex min-w-0 flex-1 items-center justify-center overflow-hidden bg-muted'
        >
          {mainSrc && (
            <img
              src={mainSrc}
              alt={file.displayName}
              onLoad={(e) => fitZoom(e.currentTarget)}
              draggable={false}
              className='max-h-full max-w-full select-none'
              style={{
                transform: `rotate(${rotate}deg) scale(${zoom / 100})`,
              }}
            />
          )}

          {index > 0 && (
            <button
              type='button'
              title={t('preview.prev')}
              onClick={() => onSwitch(index - 1)}
              className='absolute left-16 z-10 cursor-pointer text-muted-foreground transition-opacity hover:opacity-70'
            >
              <ChevronLeft className='h-14 w-14' strokeWidth={1.5} />
            </button>
          )}
          {index < files.length - 1 && (
            <button
              type='button'
              title={t('preview.next')}
              onClick={() => onSwitch(index + 1)}
              className='absolute right-16 z-10 cursor-pointer text-muted-foreground transition-opacity hover:opacity-70'
            >
              <ChevronRight className='h-14 w-14' strokeWidth={1.5} />
            </button>
          )}

          <div className='absolute bottom-5 left-1/2 flex w-[600px] max-w-[80vw] -translate-x-1/2 items-center gap-3'>
            <Slider
              value={[zoom]}
              min={1}
              max={200}
              step={1}
              onValueChange={([v]) => setZoom(v)}
            />
            <span className='w-12 text-right text-sm text-muted-foreground tabular-nums'>
              {zoom}%
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}

/** 缩略图条目：<img> 直连流接口，需各自换取 previewToken */
function PreviewThumbImage({ fileId, className }: { fileId: string; className: string }) {
  const url = usePreviewStreamUrl(fileId)
  if (!url) return null
  return <img src={url} alt='' loading='lazy' className={className} draggable={false} />
}
