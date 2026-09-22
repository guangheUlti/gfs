import { router } from '@/router'
import { useUserStore } from '@/store/user'
import { useTransferStore } from '@/store/transfer'

/**
 * 按用户传输设置决定任务添加后的行为：
 * - autoNavigateTransfer 开启（默认）：跳转传输进度页查看进度
 * - 关闭：留在当前页（文件页目录记忆负责跨页往返恢复）
 *
 * 调用方分布在 hooks 与组件中，无法统一拿 useNavigate，
 * 因此使用 createBrowserRouter 返回的 router 实例导航
 * （仅在事件回调运行时调用，不存在模块初始化顺序问题）。
 */
export type TransferTargetTab = 'uploading' | 'downloading'

export function navigateToTransferIfEnabled(
  target: TransferTargetTab = 'uploading'
): void {
  const enabled = isAutoNavigateEnabled()
  if (!enabled) return
  // 标记本次跳转由自动导航发起：全部任务完成后自动跳回文件页
  useTransferStore.getState().setAutoArrivedOnTransfer(true)
  // 目标 tab 跟随任务类型：下载任务直达「下载中」，上传直达「上传中」
  router.navigate(`/transfer?tab=${target}`)
}

/** 传输设置是否开启「任务添加后自动跳转进度页」（老数据缺省按开启） */
export function isAutoNavigateEnabled(): boolean {
  const setting = useUserStore.getState().transferSetting
  return setting ? setting.autoNavigateTransfer !== false : true
}

/**
 * 任务完成时回调：仅在「因自动导航进入传输页」且已无进行中任务时
 * 自动跳回文件页；用户手动切到传输页（标记为 false）不会触发
 */
export function navigateBackFromAutoTransferIfIdle(): void {
  const store = useTransferStore.getState()
  if (!store.autoArrivedOnTransfer) return
  const hasActive =
    store.getUploadingTasks().length > 0 || store.getDownloadingTasks().length > 0
  if (hasActive) return
  store.setAutoArrivedOnTransfer(false)
  router.navigate('/files')
}
