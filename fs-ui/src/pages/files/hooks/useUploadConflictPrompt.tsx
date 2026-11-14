import { useCallback, useRef, useState } from 'react'
import type {
  ConflictPolicy,
  FileConflictVO,
  UploadConflictDecision,
} from '@/types/transfer'
import { checkUploadConflicts } from '@/api/transfer'
import { UploadConflictDialog } from '@/pages/files/components/UploadConflictDialog'

/** 服务端一次最多检查的名称数量（UploadConflictQry @Size(max=500)） */
const NAME_BATCH_SIZE = 200

const keepBothDecision = (): UploadConflictDecision => ({
  policy: 'keepBoth',
  existingDirIds: {},
})

/**
 * 取出直接落到目标目录的顶层名称：
 * 普通文件取文件名，拖入/选择的文件夹取路径第一段（如「外语/a/b.txt」→「外语」）。
 */
function collectTopLevelNames(files: File[]): {
  fileNames: string[]
  dirNames: string[]
} {
  const fileNames = new Set<string>()
  const dirNames = new Set<string>()
  files.forEach((file) => {
    const path = file.webkitRelativePath || file.name
    const slash = path.indexOf('/')
    if (slash < 0) {
      fileNames.add(file.name)
    } else {
      dirNames.add(path.slice(0, slash))
    }
  })
  return { fileNames: Array.from(fileNames), dirNames: Array.from(dirNames) }
}

/**
 * 上传前询问同名冲突策略。
 *
 * 顶层文件和顶层文件夹都会检测：文件冲突可选覆盖，文件夹冲突选择「替换」时
 * 合并进已有目录（同名文件覆盖、其余保留），选「保留两者」由服务端自动重命名。
 * 检测失败（网络/权限）按「保留两者」继续，宁可多生成一个重命名副本，
 * 也不要因为一次探测失败阻断整批上传。
 */
export function useUploadConflictPrompt() {
  const [conflicts, setConflicts] = useState<FileConflictVO[]>([])
  const [open, setOpen] = useState(false)
  const resolverRef = useRef<
    ((decision: UploadConflictDecision | null) => void) | null
  >(null)

  const settle = useCallback(
    (policy: ConflictPolicy | null) => {
      setOpen(false)
      const resolve = resolverRef.current
      resolverRef.current = null
      if (!resolve) return
      if (policy === null) {
        resolve(null)
        return
      }
      // 用无原型对象当映射，避免名为 __proto__ 之类的目录破坏键值赋值
      const existingDirIds: Record<string, string> = Object.create(null)
      if (policy === 'replace') {
        conflicts.forEach((conflict) => {
          if (conflict.isDir) existingDirIds[conflict.fileName] = conflict.fileId
        })
      }
      resolve({ policy, existingDirIds })
    },
    [conflicts]
  )

  const resolveUploadConflicts = useCallback(
    async (
      parentId: string | undefined,
      files: File[]
    ): Promise<UploadConflictDecision | null> => {
      const { fileNames, dirNames } = collectTopLevelNames(files)
      if (fileNames.length === 0 && dirNames.length === 0) {
        return keepBothDecision()
      }

      const found: FileConflictVO[] = []
      try {
        for (let i = 0; i < fileNames.length; i += NAME_BATCH_SIZE) {
          found.push(
            ...(await checkUploadConflicts(
              parentId,
              fileNames.slice(i, i + NAME_BATCH_SIZE)
            ))
          )
        }
        for (let i = 0; i < dirNames.length; i += NAME_BATCH_SIZE) {
          found.push(
            ...(await checkUploadConflicts(
              parentId,
              [],
              dirNames.slice(i, i + NAME_BATCH_SIZE)
            ))
          )
        }
      } catch (error) {
        console.warn('检查同名文件失败，按保留两者处理', error)
        return keepBothDecision()
      }

      if (found.length === 0) return keepBothDecision()

      setConflicts(found)
      setOpen(true)
      return new Promise<UploadConflictDecision | null>((resolve) => {
        resolverRef.current = resolve
      })
    },
    []
  )

  const conflictDialog = (
    <UploadConflictDialog
      open={open}
      conflicts={conflicts}
      onReplace={() => settle('replace')}
      onKeepBoth={() => settle('keepBoth')}
      onOpenChange={(next) => {
        if (!next) settle(null)
      }}
    />
  )

  return { resolveUploadConflicts, conflictDialog }
}
