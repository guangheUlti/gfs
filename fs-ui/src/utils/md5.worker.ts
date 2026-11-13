import SparkMD5 from 'spark-md5'

/**
 * MD5 计算 Worker。
 *
 * 原本在主线程用 SparkMD5 逐块读文件算哈希：20–100MB 的文件会让界面卡住 0.5–2 秒
 * （上传开始前的空白期就是这么来的）。放到 Worker 后算法与结果完全不变，
 * 主线程只等一个消息。
 */

type WorkerRequest =
  | { id: number; kind: 'file'; file: File; full: boolean }
  | { id: number; kind: 'blob'; blob: Blob }

interface WorkerScope {
  onmessage: ((event: MessageEvent<WorkerRequest>) => void) | null
  postMessage: (message: unknown) => void
}

const scope = self as unknown as WorkerScope

/** 每次读取的切片大小：2MB，兼顾内存与 FileReader/arrayBuffer 开销 */
const READ_CHUNK_SIZE = 2 * 1024 * 1024
/** 采样指纹的采样点大小 */
const SAMPLE_SIZE = 2 * 1024 * 1024

/** 顺序切片读取整段内容并累积 MD5 */
async function appendRange(
  spark: SparkMD5.ArrayBuffer,
  blob: Blob,
  start: number,
  end: number
): Promise<void> {
  for (let offset = start; offset < end; offset += READ_CHUNK_SIZE) {
    const slice = blob.slice(offset, Math.min(offset + READ_CHUNK_SIZE, end))
    spark.append(await slice.arrayBuffer())
  }
}

async function fullMd5(blob: Blob): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer()
  await appendRange(spark, blob, 0, blob.size)
  return spark.end()
}

/** 大文件采样指纹：头/中/尾各 2MB + 大小 + 修改时间，与主线程旧实现保持一致 */
async function fastFingerprint(file: File): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer()

  await appendRange(spark, file, 0, Math.min(SAMPLE_SIZE, file.size))

  if (file.size > SAMPLE_SIZE * 3) {
    const middle = Math.floor(file.size / 2)
    await appendRange(
      spark,
      file,
      middle - Math.floor(SAMPLE_SIZE / 2),
      middle + Math.floor(SAMPLE_SIZE / 2)
    )
  }

  if (file.size > SAMPLE_SIZE * 2) {
    await appendRange(
      spark,
      file,
      Math.max(0, file.size - SAMPLE_SIZE),
      file.size
    )
  }

  const sampled = spark.end()
  return SparkMD5.hash(`${file.size}-${file.lastModified}-${sampled}`)
}

scope.onmessage = async (event) => {
  const request = event.data
  try {
    const md5 =
      request.kind === 'blob'
        ? await fullMd5(request.blob)
        : request.full
          ? await fullMd5(request.file)
          : await fastFingerprint(request.file)
    scope.postMessage({ id: request.id, md5 })
  } catch (error) {
    scope.postMessage({
      id: request.id,
      error: error instanceof Error ? error.message : String(error),
    })
  }
}
