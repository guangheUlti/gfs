import type { TaskStatus, TransferTask } from '@/types/transfer'

/**
 * 状态转换规则映射表
 */
export const VALID_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  idle: ['initialized', 'cancelled'],
  // 允许直接到 merging/completed：状态快照可能把本地状态滞后回 initialized，
  // 此时后端后续的 merging/complete 事件仍应能把任务推进到终态
  initialized: [
    'checking',
    'downloading',
    'merging',
    'completed',
    'failed',
    'cancelled',
  ],
  checking: ['uploading', 'merging', 'completed', 'failed', 'cancelled'],
  // uploading → completed：合并很快时 SSE 可能只发来 complete 而错过 merging
  uploading: ['paused', 'merging', 'completed', 'failed', 'cancelled'],
  downloading: ['paused', 'merging', 'completed', 'failed', 'cancelled'],
  paused: ['uploading', 'downloading', 'cancelled'],
  merging: ['completed', 'failed'],
  completed: [],
  failed: ['initialized'],
  cancelled: [],
}

export function canTransition(from: TaskStatus, to: TaskStatus): boolean {
  if (from === to) return true
  const validTargets = VALID_TRANSITIONS[from]
  return validTargets.includes(to)
}

export function transition(
  task: TransferTask,
  to: TaskStatus
): TransferTask | null {
  if (!canTransition(task.status, to)) {
    return null
  }

  if (task.status === to) {
    return task
  }

  return {
    ...task,
    status: to,
    updatedAt: Date.now(),
  }
}

export const stateMachine = {
  canTransition,
  transition,
}
