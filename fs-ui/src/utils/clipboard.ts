import i18n from '@/i18n'
import { toast } from 'sonner'

/**
 * 默认命名空间下的提示文案（common 命名空间无 copied/copyFailed 键，
 * 调用方各自的文案键分散在 files/settings/storage 等命名空间，
 * 这里统一用 files 命名空间，该命名空间全局加载）
 */
const tCommon = (key: 'copied' | 'copyFailed') => i18n.t(`files:${key}`)

/**
 * 降级复制：隐藏 textarea + execCommand('copy')
 *
 * 注意事项（都是实测踩过的坑）：
 * - 不能用 display:none / visibility:hidden：部分浏览器拒绝对不可见元素复制
 * - 必须真的发生文本选区：仅 select() 在移动端不可靠，需补 setSelectionRange
 * - textarea 若不在焦点链上，某些浏览器（尤其 iframe/弹窗内）会静默失败，
 *   因此先 focus 再 select
 * - execCommand 虽被标记废弃，但它是唯一同步的剪贴板 API，在
 *   非安全上下文（http 部署）下没有替代品
 */
function copyViaExecCommand(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  // 防止 iOS 弹出输入法：设置只读
  textarea.setAttribute('readonly', '')
  // 移出可视区域但保持可渲染（不能用 display:none）
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.width = '2em'
  textarea.style.height = '2em'
  textarea.style.padding = '0'
  textarea.style.border = 'none'
  textarea.style.outline = 'none'
  textarea.style.boxShadow = 'none'
  textarea.style.background = 'transparent'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)

  const activeElement = document.activeElement
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, text.length)

  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }

  document.body.removeChild(textarea)
  // 恢复焦点，避免打断用户操作
  if (activeElement instanceof HTMLElement) {
    activeElement.focus()
  }
  return ok
}

/**
 * 复制文本到剪贴板（HTTP 环境兼容）
 *
 * navigator.clipboard 仅在安全上下文（HTTPS / localhost）可用，
 * 生产环境走 http 部署时该 API 为 undefined，直接调用会抛错。
 *
 * @returns 是否复制成功
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (!text) return false

  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒等情况，继续走降级
    }
  }

  return copyViaExecCommand(text)
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
