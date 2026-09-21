import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2 } from 'lucide-react'
import request from '@/api/request'
import { Progress } from '@/components/ui/progress'

/**
 * 重启探测专用：/apis/system/jvm-memory 的静默版 GET。
 * showErrorMessage=false 让 axios 拦截器在网络错误/401 时不弹全局 toast
 * （重启期间请求必然失败，弹窗只会刷屏），仅靠 Promise reject 驱动重试。
 */
function getJvmMemoryWithProbe() {
  return request.get('/apis/system/jvm-memory', { showErrorMessage: false })
}

/** 虚拟进度：预计探测等待时间内线性逼近 92%，成功后跳 100% */
const EXPECTED_RESTART_MS = 25_000
const TICK_MS = 200
/** 进度上限（重启成功才到 100） */
const VIRTUAL_CAP = 92
/** 探测轮询间隔：重启中放宽，成功一次即确认 */
const POLL_INTERVAL_MS = 2_000
/** 总超时：超时后提示用户手动刷新，不再无限等待 */
const PROBE_TIMEOUT_MS = 180_000

interface RestartOverlayProps {
  /** 探测成功回调（刷新页面数据用） */
  onRecovered?: () => void
  /** 用户点击关闭（成功态下收起遮罩，父组件卸载本组件） */
  onClose: () => void
}

/**
 * 重启等待遮罩：虚拟进度条 + 后端存活探测。
 * 由父组件条件渲染（重启触发后挂载，关闭时卸载），状态随挂载自然重置。
 *
 * 后端重启会中断所有连接且无进度可查，故用「时间驱动的虚拟进度 +
 * 健康探测」模型：进度按预期耗时渐近推进（最多到 92% 不撒谎），
 * 后台每 2s 探测一次 /apis/system/jvm-memory（走 axios 实例的完整
 * 鉴权链路），成功即判定重启完成，进度跳 100% 展示成功态。
 */
export function RestartOverlay({ onClose, onRecovered }: RestartOverlayProps) {
  const { t } = useTranslation('settings')
  const [progress, setProgress] = useState(0)
  /** pending: 探测中 | success: 重启完成 | timeout: 探测超时 */
  const [phase, setPhase] = useState<'pending' | 'success' | 'timeout'>('pending')
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    const startedAt = Date.now()

    // 虚拟进度：随时间渐近 VIRTUAL_CAP，不回头、不超卖
    timerRef.current = window.setInterval(() => {
      const elapsed = Date.now() - startedAt
      const ratio = Math.min(elapsed / EXPECTED_RESTART_MS, 0.97)
      setProgress(Math.min(VIRTUAL_CAP, Math.round(ratio * VIRTUAL_CAP)))
    }, TICK_MS)

    const probe = async (): Promise<void> => {
      // 必须先观察到后端下线一次，才能把后续探测成功判定为「新实例已就绪」；
      // 否则点击瞬间后端尚未退出，第一轮探测会误判为重启完成
      let sawDown = false
      while (!cancelled) {
        if (Date.now() - startedAt > PROBE_TIMEOUT_MS) {
          if (!cancelled) setPhase('timeout')
          return
        }
        try {
          // showErrorMessage=false：重启期间请求必然失败，网络层错误
          // 不弹全局 toast，只静默重试；401 也仅意味后端尚未就绪
          await getJvmMemoryWithProbe()
          if (sawDown) {
            // 已观察到下线且现在能通：新实例就绪
            if (!cancelled) {
              setProgress(100)
              setPhase('success')
              onRecovered?.()
              if (timerRef.current !== null) {
                window.clearInterval(timerRef.current)
                timerRef.current = null
              }
            }
            return
          }
          // 未观察到下线前成功：旧实例仍在响应，继续等它退出
        } catch {
          sawDown = true
          // 后端仍在重启，等待下一轮
        }
        await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS))
      }
    }
    void probe()

    return () => {
      cancelled = true
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
    // 仅挂载时启动一次探测循环
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div
      className='fixed inset-0 z-[100] flex items-center justify-center bg-background/80 backdrop-blur-sm'
      role='alertdialog'
      aria-modal='true'
      aria-label={t('systemManagement.restartOverlayTitle')}
    >
      <div className='mx-4 w-full max-w-md rounded-lg border bg-background p-6 shadow-lg'>
        {phase === 'success' ? (
          <div className='flex flex-col items-center gap-3 py-2 text-center'>
            <CheckCircle2 className='size-10 text-emerald-500' />
            <p className='text-sm font-medium'>
              {t('systemManagement.restartDone')}
            </p>
            <p className='text-xs text-muted-foreground'>
              {t('systemManagement.restartDoneDesc')}
            </p>
            <button
              type='button'
              onClick={onClose}
              className='mt-2 inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90'
            >
              {t('systemManagement.restartDoneAction')}
            </button>
          </div>
        ) : (
          <div className='space-y-4'>
            <div>
              <p className='text-sm font-medium'>
                {t('systemManagement.restartOverlayTitle')}
              </p>
              <p className='mt-1 text-xs text-muted-foreground'>
                {phase === 'timeout'
                  ? t('systemManagement.restartProbeTimeout')
                  : t('systemManagement.restartingTip')}
              </p>
            </div>
            <Progress value={progress} className='h-2' />
            <p className='text-right text-xs tabular-nums text-muted-foreground'>
              {progress}%
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
