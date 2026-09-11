import { useQuery } from '@tanstack/react-query'
import { listServiceSettings } from '@/api/service'

/**
 * 对外文件服务配置列表 + 实时运行状态，10s 轮询刷新状态徽标
 */
export function useServiceSettings() {
  return useQuery({
    queryKey: ['serviceSettings'],
    queryFn: listServiceSettings,
    refetchInterval: 10_000,
  })
}
