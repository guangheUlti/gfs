import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'

export interface MarqueeRect {
  top: number
  left: number
  width: number
  height: number
}

interface UseMarqueeSelectionOptions {
  /** 滚动容器：命中测试只在它内部查找文件项 */
  containerRef: RefObject<HTMLElement | null>
  /** 为 false 时完全不响应（如空列表、加载态） */
  enabled?: boolean
  /** 框选过程中的实时结果，参数是命中的文件 id 列表 */
  onChange: (ids: string[]) => void
}

/** 起拖后位移超过该阈值才算框选，否则仍按「点击空白处取消选中」处理 */
const DRAG_THRESHOLD = 4

/**
 * 鼠标框选（橡皮筋选择）。
 *
 * 两条硬约束决定了它的适用范围：
 * 1. 只在 `pointerType === 'mouse'` 下生效——触屏上「拖动」就是滚动列表，两者无法共存；
 * 2. 只从空白处起拖——从文件上按下并拖动已经是「拖拽移动到文件夹」，不能抢。
 *
 * 矩形用视口坐标绘制（配合 `position: fixed`），命中测试同样走
 * `getBoundingClientRect`，因此容器滚动时无需做任何坐标换算。
 */
export function useMarqueeSelection({
  containerRef,
  enabled = true,
  onChange,
}: UseMarqueeSelectionOptions) {
  const [rect, setRect] = useState<MarqueeRect | null>(null)
  const startRef = useRef<{ x: number; y: number } | null>(null)
  const activeRef = useRef(false)
  /** 框选松手后浏览器仍会补发一次 click，用它挡住「点击空白处取消选中」 */
  const suppressClickRef = useRef(false)
  const suppressTimerRef = useRef<number | null>(null)

  // onChange 每次渲染都是新引用，转存到 ref 以免把下面的回调全部带崩
  const onChangeRef = useRef(onChange)
  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  const hitTest = useCallback(
    (r: MarqueeRect) => {
      const container = containerRef.current
      if (!container) return

      const right = r.left + r.width
      const bottom = r.top + r.height
      const ids: string[] = []

      container
        .querySelectorAll<HTMLElement>('[data-file-id]')
        .forEach((el) => {
          const b = el.getBoundingClientRect()
          // 被 display:none 隐藏的元素宽高为 0，天然跳过
          if (b.width === 0 || b.height === 0) return
          if (b.right < r.left || b.left > right) return
          if (b.bottom < r.top || b.top > bottom) return
          const id = el.dataset.fileId
          if (id) ids.push(id)
        })

      onChangeRef.current(ids)
    },
    [containerRef]
  )

  const detach = useCallback(() => {
    window.removeEventListener('pointermove', handleMoveRef.current)
    window.removeEventListener('pointerup', handleUpRef.current)
    window.removeEventListener('pointercancel', handleUpRef.current)
  }, [])

  const handleMove = useCallback(
    (e: PointerEvent) => {
      const start = startRef.current
      if (!start) return

      if (!activeRef.current) {
        const moved =
          Math.abs(e.clientX - start.x) >= DRAG_THRESHOLD ||
          Math.abs(e.clientY - start.y) >= DRAG_THRESHOLD
        if (!moved) return
        activeRef.current = true
        document.body.style.userSelect = 'none'
      }

      const next: MarqueeRect = {
        left: Math.min(start.x, e.clientX),
        top: Math.min(start.y, e.clientY),
        width: Math.abs(e.clientX - start.x),
        height: Math.abs(e.clientY - start.y),
      }
      setRect(next)
      hitTest(next)
    },
    [hitTest]
  )

  const handleUp = useCallback(() => {
    if (activeRef.current) {
      suppressClickRef.current = true
      // 兜底：若这次松手没有补发 click（比如在容器外松手），也要把标记放开
      if (suppressTimerRef.current !== null) {
        window.clearTimeout(suppressTimerRef.current)
      }
      suppressTimerRef.current = window.setTimeout(() => {
        suppressClickRef.current = false
        suppressTimerRef.current = null
      }, 0)
    }
    startRef.current = null
    activeRef.current = false
    document.body.style.userSelect = ''
    setRect(null)
    detach()
  }, [detach])

  // handleMove / handleUp 需要在 detach 里被引用，而 detach 又先于它们声明，
  // 因此统一挂到 ref 上，保证增删监听器用的是同一个函数引用
  const handleMoveRef = useRef(handleMove)
  const handleUpRef = useRef(handleUp)
  useEffect(() => {
    handleMoveRef.current = handleMove
    handleUpRef.current = handleUp
  }, [handleMove, handleUp])

  const onMarqueePointerDown = useCallback(
    (e: React.PointerEvent) => {
      if (!enabled) return
      if (e.pointerType !== 'mouse') return
      if (e.button !== 0) return

      const target = e.target as HTMLElement
      // 从文件上起拖是「拖拽移动」；表单控件、表头、已展开的菜单也不该触发框选
      if (
        target.closest('[data-file-id]') ||
        target.closest('button, a, input, textarea, select, thead, [role="menu"]')
      ) {
        return
      }

      startRef.current = { x: e.clientX, y: e.clientY }
      activeRef.current = false

      window.addEventListener('pointermove', handleMoveRef.current)
      window.addEventListener('pointerup', handleUpRef.current)
      window.addEventListener('pointercancel', handleUpRef.current)
    },
    [enabled]
  )

  // 卸载时收尾，别把 userSelect 和全局监听器留在页面上
  useEffect(() => {
    return () => {
      window.removeEventListener('pointermove', handleMoveRef.current)
      window.removeEventListener('pointerup', handleUpRef.current)
      window.removeEventListener('pointercancel', handleUpRef.current)
      if (suppressTimerRef.current !== null) {
        window.clearTimeout(suppressTimerRef.current)
      }
      document.body.style.userSelect = ''
    }
  }, [])

  /** 供滚动容器的 onClick 调用：框选刚结束时吞掉这次点击 */
  const shouldSuppressClick = useCallback(() => {
    if (!suppressClickRef.current) return false
    suppressClickRef.current = false
    return true
  }, [])

  return { marqueeRect: rect, onMarqueePointerDown, shouldSuppressClick }
}
