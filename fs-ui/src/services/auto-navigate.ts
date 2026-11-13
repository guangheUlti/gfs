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

/**
 * 正在批量建任务的层数。
 * 批量上传（尤其是拖文件夹）要逐层建目录、再逐个建任务，其间任务队列会短暂为空，
 * 若此时按「已无进行中任务」判断，就会在上传实际刚开始时把用户弹回文件页。
 */
let launchDepth = 0

/** 自动跳转到传输页的时刻 */
let autoArrivedAt = 0

/**
 * 跳过来至少停留这么久才允许自动返回。
 * 小文件/小目录几百毫秒就传完，没有这条会「闪一下就弹回文件页」，
 * 看上去像压根没跳转；到点的返回交给下面的延时重检完成。
 */
const AUTO_BACK_MIN_DWELL_MS = 1500

/** 未满足最短停留时排下的延时重检定时器 */
let autoBackTimer: ReturnType<typeof setTimeout> | null = null

/** 取消待执行的自动返回重检（新一次跳转/批量开始时调用，避免旧定时器干扰） */
function clearAutoBackTimer(): void {
  if (autoBackTimer !== null) {
    clearTimeout(autoBackTimer)
    autoBackTimer = null
  }
}

/** 进入「批量建任务」阶段，期间不触发自动返回 */
export function beginTransferLaunch(): void {
  launchDepth += 1
  clearAutoBackTimer()
}

/**
 * 退出「批量建任务」阶段。
 * 批量结束即刷新计时起点：小文件往往在任务刚建完就传完了，
 * 若按到达时刻计时，差几毫秒就会「刚看到传输页又被弹回去」。
 */
export function endTransferLaunch(): void {
  launchDepth = Math.max(0, launchDepth - 1)
  if (launchDepth === 0) {
    autoArrivedAt = Date.now()
    // 整批都是小文件（一次性上传）时，任务建完可能已经没有任何进行中的传输，
    // 不会再有完成回调来触发返回，这里主动判定一次
    navigateBackFromAutoTransferIfIdle()
  }
}

export function navigateToTransferIfEnabled(
  target: TransferTargetTab = 'uploading'
): void {
  const enabled = isAutoNavigateEnabled()
  if (!enabled) return

  clearAutoBackTimer()
  autoArrivedAt = Date.now()
  // 标记本次跳转由自动导航发起：全部任务完成后自动跳回文件页
  useTransferStore.getState().setAutoArrivedOnTransfer(true)

  const { pathname, search } = router.state.location
  const currentTab = new URLSearchParams(search).get('tab')
  // 已经在该 tab 上就不重复推一条历史记录
  if (
    pathname === '/transfer' &&
    (currentTab === target || (!currentTab && target === 'uploading'))
  ) {
    return
  }
  // 目标 tab 跟随任务类型：下载任务直达「下载中」，上传直达「上传中」
  router.navigate(`/transfer?tab=${target}`)

  // 已经没有任何进行中的任务（全部走了一次性上传）时立即安排返回，
  // 否则会停在一个已经全部完成的传输页上
  navigateBackFromAutoTransferIfIdle()
}

/** 传输设置是否开启「任务添加后自动跳转进度页」（老数据缺省按开启） */
export function isAutoNavigateEnabled(): boolean {
  const setting = useUserStore.getState().transferSetting
  return setting ? setting.autoNavigateTransfer !== false : true
}

/**
 * 全部任务结束后回调：仅在「因自动导航进入传输页」且已无进行中任务时
 * 自动跳回文件页；用户手动切到传输页（标记为 false）不会触发。
 *
 * 未满足最短停留时不直接放弃，而是排一个延时重检：
 * 否则秒传完的批次会一直把用户留在传输页，等不到返回。
 */
export function navigateBackFromAutoTransferIfIdle(): void {
  const store = useTransferStore.getState()
  if (!store.autoArrivedOnTransfer) return
  // 还在批量建任务：队列暂时为空不代表上传结束，不能把用户弹回文件页
  if (launchDepth > 0) return
  const hasActive =
    store.getUploadingTasks().length > 0 || store.getDownloadingTasks().length > 0
  if (hasActive) return

  // 刚跳过来就结束：留够最短停留时间，到点再回来
  const remain = AUTO_BACK_MIN_DWELL_MS - (Date.now() - autoArrivedAt)
  if (remain > 0) {
    if (autoBackTimer !== null) return
    autoBackTimer = setTimeout(() => {
      autoBackTimer = null
      navigateBackFromAutoTransferIfIdle()
    }, remain)
    return
  }

  clearAutoBackTimer()
  store.setAutoArrivedOnTransfer(false)
  if (router.state.location.pathname !== '/files') {
    router.navigate('/files')
  }
}
