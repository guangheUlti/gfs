import type { TaskStatus, TransferTask } from '@/types/transfer'

/**
 * 状态转换规则映射表
 */
export const VALID_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  idle: ['initialized', 'cancelled'],
  initialized: ['checking', 'downloading', 'failed', 'cancelled'],
  checking: ['uploading', 'completed', 'failed', 'cancelled'],
  uploading: ['paused', 'merging', 'failed', 'cancelled'],
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
