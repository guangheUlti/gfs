import i18n from '@/i18n'
import SparkMD5 from 'spark-md5'

/**
 * 快速指纹阈值：大于此大小的文件使用快速指纹（100MB）
 */
const FAST_FINGERPRINT_THRESHOLD = 100 * 1024 * 1024

/** 主线程兜底实现的分片读取大小 */
const INLINE_CHUNK_SIZE = 2 * 1024 * 1024

/**
 * MD5 计算移到 Web Worker（见 md5.worker.ts）：
 * 大文件全量哈希不再占住主线程，上传前的界面不会再整个卡住。
 * Worker 不可用（老浏览器/加载失败）时自动退回下面的主线程实现，行为不变。
 */
interface WorkerReply {
  id: number
  md5?: string
  error?: string
}

let worker: Worker | null = null
/** Worker 创建失败或运行中崩溃后置真，后续直接走主线程实现 */
let workerUnavailable = false
let requestSeq = 0
const pendingJobs = new Map<
  number,
  { resolve: (md5: string) => void; reject: (error: Error) => void }
>()

function rejectAllPending(): void {
  const jobs = Array.from(pendingJobs.values())
  pendingJobs.clear()
  jobs.forEach((job) => job.reject(new Error('md5 worker failed')))
}

function getWorker(): Worker | null {
  if (worker || workerUnavailable) return worker
  try {
    worker = new Worker(new URL('./md5.worker.ts', import.meta.url), {
      type: 'module',
    })
    worker.onmessage = (event: MessageEvent<WorkerReply>) => {
      const job = pendingJobs.get(event.data.id)
      if (!job) return
      pendingJobs.delete(event.data.id)
      if (typeof event.data.md5 === 'string') {
        job.resolve(event.data.md5)
      } else {
        job.reject(new Error(event.data.error || 'MD5 计算失败'))
      }
    }
    worker.onerror = () => {
      workerUnavailable = true
      rejectAllPending()
      worker?.terminate()
      worker = null
    }
  } catch {
    workerUnavailable = true
    worker = null
  }
  return worker
}

/** 交给 Worker 计算；Worker 不可用或单次失败时用 fallback 兜底 */
async function runInWorker(
  message: Record<string, unknown>,
  fallback: () => Promise<string>
): Promise<string> {
  const w = getWorker()
  if (!w) return fallback()
  try {
    return await new Promise<string>((resolve, reject) => {
      const id = ++requestSeq
      pendingJobs.set(id, { resolve, reject })
      w.postMessage({ id, ...message })
    })
  } catch {
    return fallback()
  }
}

/** 主线程兜底：完整 MD5 */
function calculateFullFileMD5Inline(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const blobSlice = File.prototype.slice
    const chunks = Math.ceil(file.size / INLINE_CHUNK_SIZE)
    let currentChunk = 0
    const spark = new SparkMD5.ArrayBuffer()
    const fileReader = new FileReader()

    function loadNext() {
      const start = currentChunk * INLINE_CHUNK_SIZE
      const end = Math.min(start + INLINE_CHUNK_SIZE, file.size)
      const chunk = blobSlice.call(file, start, end)
      fileReader.readAsArrayBuffer(chunk)
    }

    fileReader.onload = (e) => {
      if (e.target?.result) {
        spark.append(e.target.result as ArrayBuffer)
        currentChunk += 1

        if (currentChunk < chunks) {
          loadNext()
        } else {
          resolve(spark.end())
        }
      }
    }

    fileReader.onerror = () => {
      reject(new Error(i18n.t('common:fileErrors.readFailed')))
    }

    loadNext()
  })
}

/** 主线程兜底：大文件采样指纹 */
function calculateFastFingerprintInline(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const sampleSize = 2 * 1024 * 1024
    const spark = new SparkMD5.ArrayBuffer()
    const fileReader = new FileReader()

    const samples: { start: number; end: number }[] = []
    samples.push({ start: 0, end: Math.min(sampleSize, file.size) })

    if (file.size > sampleSize * 3) {
      const middle = Math.floor(file.size / 2)
      samples.push({
        start: middle - Math.floor(sampleSize / 2),
        end: middle + Math.floor(sampleSize / 2),
      })
    }

    if (file.size > sampleSize * 2) {
      samples.push({
        start: Math.max(0, file.size - sampleSize),
        end: file.size,
      })
    }

    let currentSample = 0

    function loadNext() {
      if (currentSample >= samples.length) {
        const sampledMd5 = spark.end()
        resolve(
          SparkMD5.hash(`${file.size}-${file.lastModified}-${sampledMd5}`)
        )
        return
      }

      const sample = samples[currentSample]
      fileReader.readAsArrayBuffer(file.slice(sample.start, sample.end))
    }

    fileReader.onload = (e) => {
      if (e.target?.result) {
        spark.append(e.target.result as ArrayBuffer)
        currentSample += 1
        loadNext()
      }
    }

    fileReader.onerror = () => {
      reject(new Error(i18n.t('common:fileErrors.readFailed')))
    }

    loadNext()
  })
}

/** 主线程兜底：分片 MD5 */
function calculateBlobMD5Inline(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const fileReader = new FileReader()

    fileReader.onload = (e) => {
      if (e.target?.result) {
        const spark = new SparkMD5.ArrayBuffer()
        spark.append(e.target.result as ArrayBuffer)
        resolve(spark.end())
      }
    }

    fileReader.onerror = () => {
      reject(new Error(i18n.t('common:fileErrors.chunkReadFailed')))
    }

    fileReader.readAsArrayBuffer(blob)
  })
}

/**
 * 计算文件的快速指纹（采样策略）
 * 对于大文件，只计算头部、中部、尾部的 MD5，大幅提升速度
 */
export function calculateFastFingerprint(file: File): Promise<string> {
  return runInWorker({ kind: 'file', file, full: false }, () =>
    calculateFastFingerprintInline(file)
  )
}

/**
 * 计算文件的完整MD5值（用于小文件或需要精确校验的场景）
 */
export function calculateFullFileMD5(file: File): Promise<string> {
  return runInWorker({ kind: 'file', file, full: true }, () =>
    calculateFullFileMD5Inline(file)
  )
}

/**
 * 智能计算文件 MD5
 * - 小文件（< 100MB）：使用完整 MD5
 * - 大文件（>= 100MB）：使用快速指纹
 */
export function calculateFileMD5(file: File): Promise<string> {
  if (file.size >= FAST_FINGERPRINT_THRESHOLD) {
    return calculateFastFingerprint(file)
  }
  return calculateFullFileMD5(file)
}

/**
 * 计算Blob（分片）的MD5值
 */
export function calculateBlobMD5(blob: Blob): Promise<string> {
  return runInWorker({ kind: 'blob', blob }, () => calculateBlobMD5Inline(blob))
}
