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

/**
 * 跳转前的文件页地址（含 parentId/viewMode 等查询串）。
 * 返回时原样回去：不依赖文件页位置记忆，避免记忆为空或被消费后落到根目录。
 */
let returnPath: string | null = null

/**
 * 跳转前的观察窗口：小文件走一次性上传，建任务时就已经传完，
 * 此时跳过去只会看到一个没有任何进度的传输页。
 * 这段时间内整批就结束了则不跳转，留在文件页由完成回调刷新列表。
 */
const AUTO_NAVIGATE_GRACE_MS = 400

/** 观察窗口定时器与目标 tab */
let pendingNavigateTimer: ReturnType<typeof setTimeout> | null = null
let pendingNavigateTarget: TransferTargetTab = 'uploading'

/**
 * 观察窗口到点时批次仍在建任务、却还没有任何进行中的任务：
 * 说明这批多半都是一次性上传，建完可能全都已经结束。
 * 先推迟到 endTransferLaunch 再决定，避免「刚跳过去就全完成」的空白页。
 */
let deferredNavigateTarget: TransferTargetTab | null = null

function clearPendingNavigate(): void {
  if (pendingNavigateTimer !== null) {
    clearTimeout(pendingNavigateTimer)
    pendingNavigateTimer = null
  }
}

/** 进入「批量建任务」阶段，期间不触发自动返回 */
export function beginTransferLaunch(): void {
  launchDepth += 1
}

/**
 * 退出「批量建任务」阶段。
 * 批次建完才决定是否补跳：还有进行中的任务就跳过去看进度，
 * 整批已经结束就留在文件页（或从传输页立即返回），不展示空白页。
 */
export function endTransferLaunch(): void {
  launchDepth = Math.max(0, launchDepth - 1)
  if (launchDepth > 0) return

  const target = deferredNavigateTarget
  deferredNavigateTarget = null
  const store = useTransferStore.getState()
  const hasActive =
    store.getUploadingTasks().length > 0 || store.getDownloadingTasks().length > 0
  if (target && hasActive) {
    doNavigateToTransfer(target)
    return
  }
  // 整批都是小文件（一次性上传）时，任务建完可能已经没有任何进行中的传输，
  // 不会再有完成回调来触发返回，这里主动判定一次
  navigateBackFromAutoTransferIfIdle()
}

export function navigateToTransferIfEnabled(
  target: TransferTargetTab = 'uploading'
): void {
  if (!isAutoNavigateEnabled()) {
    clearPendingNavigate()
    deferredNavigateTarget = null
    return
  }

  clearPendingNavigate()
  deferredNavigateTarget = null

  const store = useTransferStore.getState()
  const hasActive =
    store.getUploadingTasks().length > 0 || store.getDownloadingTasks().length > 0

  // 队列里已经有在跑的任务（普通分片上传/下载）：立即跳，不用等观察窗口
  if (hasActive) {
    doNavigateToTransfer(target)
    return
  }

  // 还一个任务都没建出来（拖拽落点先跳转、或整批都是一次性上传且已传完）：
  // 等一个短窗口再决定，见 AUTO_NAVIGATE_GRACE_MS
  pendingNavigateTarget = target
  pendingNavigateTimer = setTimeout(() => {
    pendingNavigateTimer = null
    const current = useTransferStore.getState()
    const hasActiveNow =
      current.getUploadingTasks().length > 0 ||
      current.getDownloadingTasks().length > 0
    // 仍在建任务且暂无进度可看：推迟到批次结束，避免跳到空白传输页
    if (!hasActiveNow && launchDepth > 0) {
      deferredNavigateTarget = pendingNavigateTarget
      return
    }
    doNavigateToTransfer(pendingNavigateTarget)
  }, AUTO_NAVIGATE_GRACE_MS)
}

function doNavigateToTransfer(target: TransferTargetTab): void {
  const store = useTransferStore.getState()
  const { pathname, search } = router.state.location
  const currentTab = new URLSearchParams(search).get('tab')
  const onTargetPage =
    pathname === '/transfer' &&
    (currentTab === target || (!currentTab && target === 'uploading'))

  const inFlight =
    launchDepth > 0 ||
    store.getUploadingTasks().length > 0 ||
    store.getDownloadingTasks().length > 0

  // 已经在该 tab 上：不重复推历史记录，也不改自动跳转标记
  if (onTargetPage) return

  // 观察窗口结束时整批已经结束（一次性上传的小文件）：没有进度可看就不跳，
  // 留在文件页由 file-upload-complete 回调刷新列表
  if (!inFlight) {
    store.setAutoArrivedOnTransfer(false)
    return
  }

  // 记下来源地址（通常已带 parentId），返回时按原地址回去
  returnPath = pathname === '/files' ? `${pathname}${search}` : null
  // 标记本次跳转由自动导航发起：全部任务完成后自动跳回文件页
  store.setAutoArrivedOnTransfer(true)
  // 目标 tab 跟随任务类型：下载任务直达「下载中」，上传直达「上传中」
  // flushSync：默认 navigate 走 startTransition，上传期间主线程被哈希/分片发送
  // 占用时，路由提交会被推迟近 1 秒——用户看到的就是「停在空白传输页」。
  router.navigate(`/transfer?tab=${target}`, { flushSync: true })

  // 已经没有任何进行中的任务时立即返回，不在空白传输页上停留
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
 * 没有留观时间：任务全部完成的瞬间就返回，避免空白的传输页停顿。
 */
export function navigateBackFromAutoTransferIfIdle(): void {
  const store = useTransferStore.getState()
  if (!store.autoArrivedOnTransfer) return
  // 还在批量建任务：队列暂时为空不代表上传结束，不能把用户弹回文件页
  if (launchDepth > 0) return
  const hasActive =
    store.getUploadingTasks().length > 0 || store.getDownloadingTasks().length > 0
  if (hasActive) return

  store.setAutoArrivedOnTransfer(false)
  // 回到跳转前的那个目录，而不是裸 /files（裸地址要经过位置记忆，记忆缺失就会落到根目录）
  const backTarget = returnPath ?? '/files'
  returnPath = null
  if (router.state.location.pathname !== '/files') {
    router.navigate(backTarget, { flushSync: true })
  }
}
