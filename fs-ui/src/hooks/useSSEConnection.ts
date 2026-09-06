import { useEffect, useRef } from 'react'
import { useAuth } from '@/contexts/auth-context'
import { useTransferStore } from '@/store/transfer'

/**
 * SSE 连接 Hook
 * 自动管理 SSE 连接的生命周期，登录后即初始化（以用户为维度）
 */
export function useSSEConnection() {
  const { user, isAuthenticated } = useAuth()
  const { initSSE, disconnectSSE, sseConnected } = useTransferStore()
  const isInitializedRef = useRef(false)
  const contextRef = useRef<string | null>(null)

  useEffect(() => {
    const contextKey = user?.id ?? null

    if (isAuthenticated && contextKey) {
      if (isInitializedRef.current && contextRef.current === contextKey) {
        return
      }

      if (isInitializedRef.current) {
        disconnectSSE()
      }

      initSSE(contextKey)
      isInitializedRef.current = true
      contextRef.current = contextKey
    }

    return () => {
      if (!isAuthenticated || !user?.id) {
        disconnectSSE()
        isInitializedRef.current = false
        contextRef.current = null
      }
    }
  }, [isAuthenticated, user?.id])

  return { sseConnected }
}
