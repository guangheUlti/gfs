import { useEffect, useState } from 'react'
import { getPreviewToken } from '@/api/file'
import type { FileItem } from '@/types/file'

/** 各预览 viewer 的统一 props：files 为同类型文件集合，index 为当前索引；startEdit 为 true 时直接进入编辑模式 */
export interface PreviewViewerProps {
  files: FileItem[]
  index: number
  onSwitch: (index: number) => void
  startEdit?: boolean
}

/**
 * 预览类型分发（前端镜像后端 FileTypeEnum）：
 * - image/video/audio/pdf 走各自 viewer
 * - word/ppt 后端流转内已转 PDF（OfficeToPdfConverter），统一按 pdf 视图处理
 * - markdown 走 Markdown 渲染，text/code 走 CodeMirror
 * - excel/archive/tif/drawio 内嵌后端现成 Thymeleaf 预览页
 */
export type PreviewKind =
  | 'image'
  | 'video'
  | 'audio'
  | 'pdf'
  | 'code'
  | 'markdown'
  | 'embedded'
  | 'unsupported'

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg']
const VIDEO_EXTS = ['mp4', 'avi', 'mkv', 'mov', 'wmv', 'flv', 'webm']
const AUDIO_EXTS = ['mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'wma']
const PDF_EXTS = ['pdf']
const PDF_CONVERTED_EXTS = ['doc', 'docx', 'ppt', 'pptx']
const MARKDOWN_EXTS = ['md', 'markdown']
const CODE_EXTS = [
  'java', 'js', 'jsx', 'ts', 'tsx', 'py', 'c', 'cpp', 'h', 'hpp', 'cc', 'cxx',
  'html', 'css', 'scss', 'sass', 'less', 'vue', 'php', 'go', 'rs', 'rb',
  'swift', 'kt', 'scala', 'json', 'xml', 'sql', 'sh', 'bash', 'bat', 'ps1',
  'cs', 'toml',
]
const TEXT_EXTS = ['txt', 'log', 'ini', 'properties', 'yaml', 'yml', 'conf']
const EMBEDDED_EXTS = [
  'xls', 'xlsx', 'csv',
  'zip', 'rar', '7z', 'tar', 'gzip',
  'tif', 'tiff', 'drawio',
]

export function getPreviewKind(suffix: string | undefined): PreviewKind {
  const ext = (suffix ?? '').toLowerCase()
  if (IMAGE_EXTS.includes(ext)) return 'image'
  if (VIDEO_EXTS.includes(ext)) return 'video'
  if (AUDIO_EXTS.includes(ext)) return 'audio'
  if (PDF_EXTS.includes(ext) || PDF_CONVERTED_EXTS.includes(ext)) return 'pdf'
  if (MARKDOWN_EXTS.includes(ext)) return 'markdown'
  if (CODE_EXTS.includes(ext) || TEXT_EXTS.includes(ext)) return 'code'
  if (EMBEDDED_EXTS.includes(ext)) return 'embedded'
  return 'unsupported'
}

/** 可在线编辑（读/改文本内容）：文本 + 代码 + Markdown，镜像后端可编辑白名单 */
export function isEditableSuffix(suffix: string | undefined): boolean {
  const ext = (suffix ?? '').toLowerCase()
  return TEXT_EXTS.includes(ext) || CODE_EXTS.includes(ext) || MARKDOWN_EXTS.includes(ext)
}

/**
 * 流式预览地址解析：流接口已纳入防盗链拦截，<img>/<video>/<audio>/<iframe> 带不了登录头，
 * 需先换取短时 previewToken 再拼 URL；token 有效期 5 分钟，本地缓存 4 分钟内复用
 */
const STREAM_URL_TTL_MS = 4 * 60 * 1000
const streamUrlCache = new Map<string, { url: string; expireAt: number }>()

async function resolvePreviewStreamUrl(fileId: string): Promise<string> {
  const cached = streamUrlCache.get(fileId)
  if (cached && cached.expireAt > Date.now()) return cached.url
  const token = await getPreviewToken(fileId)
  const url = `${import.meta.env.VITE_API_BASE_URL}/api/file/stream/preview/${fileId}?previewToken=${encodeURIComponent(token)}`
  streamUrlCache.set(fileId, { url, expireAt: Date.now() + STREAM_URL_TTL_MS })
  return url
}

export function usePreviewStreamUrl(fileId: string): string | undefined {
  const [url, setUrl] = useState<string | undefined>(
    () => streamUrlCache.get(fileId)?.url
  )
  useEffect(() => {
    let alive = true
    resolvePreviewStreamUrl(fileId)
      .then((u) => {
        if (alive) setUrl(u)
      })
      .catch(() => {
        if (alive) setUrl(undefined)
      })
    return () => {
      alive = false
    }
  }, [fileId])
  return url
}

/** 从文件集合中筛出与指定文件同类型（同 PreviewKind）的文件，用于预览切换 */
export function filterSameKind(files: FileItem[], kind: PreviewKind): FileItem[] {
  if (kind === 'unsupported') return []
  return files.filter((f) => !f.isDir && getPreviewKind(f.suffix) === kind)
}
