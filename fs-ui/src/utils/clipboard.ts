import i18n from '@/i18n'
import { toast } from 'sonner'

/**
 * 默认命名空间下的提示文案（common 命名空间无 copied/copyFailed 键，
 * 调用方各自的文案键分散在 files/settings/storage 等命名空间，
 * 这里统一用 files 命名空间，该命名空间全局加载）
 */
const tCommon = (key: 'copied' | 'copyFailed') => i18n.t(`files:${key}`)

/**
 * 复制文本到剪贴板（HTTP 环境兼容）
 *
 * navigator.clipboard 仅在安全上下文（HTTPS / localhost）可用，
 * 生产环境走 http 部署时该 API 为 undefined，直接调用会抛错。
 * 降级方案：隐藏 textarea + document.execCommand('copy')。
 *
 * @returns 是否复制成功
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒等情况，继续走降级
    }
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    // 避免页面滚动/闪烁：移出可视区域但不 display:none（部分浏览器不复制不可见元素）
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    textarea.style.left = '-9999px'
    textarea.setAttribute('readonly', '')
    document.body.appendChild(textarea)
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}

/**
 * 复制并弹出成功/失败 toast
 */
export async function copyToClipboardWithToast(text: string): Promise<boolean> {
  const ok = await copyToClipboard(text)
  if (ok) {
    toast.success(tCommon('copied'))
  } else {
    toast.error(tCommon('copyFailed'))
  }
  return ok
}
