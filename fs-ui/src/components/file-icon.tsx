import { cn } from '@/lib/utils'

interface FileIconProps {
  type: string
  className?: string
  size?: number
}

/**
 * ffs 风格的彩色文件类型图标（按扩展名精确匹配，未命中时按类别兜底）。
 * 图标素材来自 D:\workspace\lab\ffs 项目的 assets/file 目录。
 */
const assetModules = import.meta.glob<string>('../assets/file-icons/*', {
  eager: true,
  query: '?url',
  import: 'default',
})

/** 文件名去掉 file_ 前缀和扩展名得到图标 key，如 file_pdf.png -> pdf、dir.png -> dir */
const iconUrlByKey: Record<string, string> = {}
for (const [path, url] of Object.entries(assetModules)) {
  const fileName = path.split('/').pop()!.toLowerCase()
  const key = fileName.replace(/^file_/, '').replace(/\.(png|svg)$/, '')
  iconUrlByKey[key] = url
}

/** 扩展名 -> 图标 key 的精确映射（对齐 ffs 的 fileImgMap，并补充常见扩展名） */
const EXACT_EXT_ICON: Record<string, string> = {
  // 脚本与编程语言
  bat: 'powershell',
  cmd: 'powershell',
  ps1: 'powershell',
  c: 'c',
  h: 'c',
  'c++': 'c++',
  cpp: 'c++',
  cc: 'c++',
  cxx: 'c++',
  hpp: 'c++',
  // c# 素材命名为 csharp：原文件名里的 # 会被 URL 解析成 fragment
  'c#': 'csharp',
  cs: 'csharp',
  css: 'css',
  scss: 'scss',
  sass: 'sass',
  less: 'less',
  styl: 'stylus',
  go: 'go',
  py: 'python',
  java: 'java',
  jar: 'jar',
  kt: 'kotlin',
  js: 'js',
  jsx: 'js',
  m: 'objective_c',
  jsp: 'jsp',
  php: 'php',
  r: 'r',
  rs: 'rust',
  swift: 'swift',
  lua: 'lua',
  vue: 'vue',
  sh: 'shell',
  bash: 'shell',
  zsh: 'shell',
  // 数据与配置
  json: 'json',
  xml: 'xml',
  html: 'html',
  htm: 'html',
  sql: 'sql',
  properties: 'properties',
  conf: 'nginx',
  yaml: 'yaml',
  yml: 'yaml',
  toml: 'yaml',
  ini: 'txt',
  cfg: 'txt',
  env: 'txt',
  csv: 'csv',
  tsv: 'csv',
  // 文档
  pdf: 'pdf',
  doc: 'word',
  docx: 'word',
  odt: 'word',
  pages: 'word',
  rtf: 'rtf',
  md: 'markdown',
  markdown: 'markdown',
  txt: 'txt',
  log: 'log',
  chm: 'chm',
  // 表格与演示
  xls: 'excel',
  xlsx: 'excel',
  xlsm: 'excel',
  xlsb: 'excel',
  ods: 'excel',
  numbers: 'excel',
  ppt: 'ppt',
  pptx: 'ppt',
  odp: 'ppt',
  key: 'ppt',
  // 二进制与压缩
  exe: 'exe',
  msi: 'exe',
  dmg: 'dmg',
  zip: 'zip',
  '7z': '7z',
  tar: 'tar',
  rar: 'rar',
  gz: 'zip',
  bz2: 'zip',
  xz: 'zip',
  psd: 'ps',
  ps: 'ps',
}

/** 无精确图标时的扩展名类别兜底（对齐 ffs 的通用 image/music/video 图标） */
const IMAGE_EXTS = [
  'jpg',
  'jpeg',
  'png',
  'gif',
  'bmp',
  'webp',
  'ico',
  'tiff',
  'tif',
  'heic',
  'heif',
]
const VIDEO_EXTS = [
  'mp4',
  'avi',
  'mov',
  'wmv',
  'flv',
  'mkv',
  'webm',
  'm4v',
  'mpg',
  'mpeg',
  '3gp',
  'ogv',
]
const AUDIO_EXTS = [
  'mp3',
  'wav',
  'flac',
  'aac',
  'ogg',
  'wma',
  'm4a',
  'opus',
  'ape',
  'alac',
]
const ARCHIVE_EXTS = ['iso', 'zst', 'lz4', 'cab', 'gz', 'bz2', 'xz']
const DATABASE_EXTS = ['db', 'sqlite', 'sqlite3', 'mdb', 'accdb', 'dbf', 'mdf', 'ldf']

export const FileIcon: React.FC<FileIconProps> = ({
  type,
  className = '',
  size = 48,
}) => {
  const iconKey = getIconKey(type)

  return (
    <img
      src={iconUrlByKey[iconKey] ?? iconUrlByKey['unknown']}
      width={size}
      height={size}
      className={cn('select-none object-contain', className)}
      draggable={false}
      alt=''
      loading='lazy'
    />
  )
}

/**
 * 根据扩展名解析图标 key；解析不出来时返回 unknown
 */
function getIconKey(type: string): string {
  const lowerType = type.toLowerCase()

  if (lowerType === 'dir' || lowerType === 'folder') return 'dir'

  const exact = EXACT_EXT_ICON[lowerType]
  if (exact && iconUrlByKey[exact]) return exact

  // 图片（svg/gif 用 ffs 专属图标，其余走通用图片图标）
  if (IMAGE_EXTS.includes(lowerType)) return 'image'
  if (lowerType === 'svg') return iconUrlByKey['svg'] ? 'svg' : 'image'
  if (lowerType === 'gif') return iconUrlByKey['gif'] ? 'gif' : 'image'

  if (VIDEO_EXTS.includes(lowerType)) return 'video'
  if (AUDIO_EXTS.includes(lowerType)) return 'music'
  if (ARCHIVE_EXTS.includes(lowerType)) return 'zip'
  if (DATABASE_EXTS.includes(lowerType)) return 'sql'

  // 其余文本类：未知编程语言扩展名按文本文件处理
  if (
    [
      'rb',
      'dart',
      'scala',
      'erl',
      'ex',
      'exs',
      'hs',
      'clj',
      'groovy',
      'pl',
      'vb',
      'fs',
      'fsi',
    ].includes(lowerType)
  ) {
    return 'txt'
  }

  return 'unknown'
}
