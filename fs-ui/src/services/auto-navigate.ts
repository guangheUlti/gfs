import { router } from '@/router'
import { useUserStore } from '@/store/user'

/**
 * 按用户传输设置决定任务添加后的行为：
 * - autoNavigateTransfer 开启（默认）：跳转传输进度页查看进度
 * - 关闭：留在当前页（文件页目录记忆负责跨页往返恢复）
 *
 * 调用方分布在 hooks 与组件中，无法统一拿 useNavigate，
 * 因此使用 createBrowserRouter 返回的 router 实例导航
 * （仅在事件回调运行时调用，不存在模块初始化顺序问题）。
 */
export function navigateToTransferIfEnabled(): void {
  const setting = useUserStore.getState().transferSetting
  const enabled = setting ? setting.autoNavigateTransfer !== false : true
  if (!enabled) return
  router.navigate('/transfer')
}
