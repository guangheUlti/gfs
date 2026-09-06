export interface FileWithPath extends File {
  webkitRelativePath: string
}

/**
 * readEntries 单次调用最多返回 100 条（规范行为），读到空数组才算读完。
 */
function readAllEntries(
  reader: FileSystemDirectoryReader
): Promise<FileSystemEntry[]> {
  return new Promise((resolve) => {
    const all: FileSystemEntry[] = []
    const readBatch = () => {
      reader.readEntries(
        (batch) => {
          if (batch.length === 0) {
            resolve(all)
            return
          }
          all.push(...batch)
          readBatch()
        },
        // 读取失败按已读到的处理，不让整个拖放卡死
        () => resolve(all)
      )
    }
    readBatch()
  })
}

/**
 * 递归读取拖拽项：文件夹展开为其中的全部文件并保留相对路径
 * （顶层目录名同样保留前缀，与 webkitdirectory 选择的 webkitRelativePath 一致）。
 */
async function readEntry(
  entry: FileSystemEntry,
  path: string,
  files: FileWithPath[]
): Promise<void> {
  if (entry.isFile) {
    const file = await new Promise<File | null>((resolve) => {
      ;(entry as FileSystemFileEntry).file(
        (f) => resolve(f),
        () => resolve(null)
      )
    })
    if (!file) return

    // 直接复用原始 File 对象，避免重新构造 blob
    Object.defineProperty(file, 'webkitRelativePath', {
      value: path + file.name,
      writable: false,
      configurable: true,
    })
    files.push(file as FileWithPath)
    return
  }

  if (entry.isDirectory) {
    const reader = (entry as FileSystemDirectoryEntry).createReader()
    const children = await readAllEntries(reader)
    // 并行处理同级子项
    await Promise.all(
      children.map((child) => readEntry(child, path + entry.name + '/', files))
    )
  }
}

/**
 * 从拖放的 DataTransfer 收集全部文件；拖入文件夹时递归展开，
 * 文件的 webkitRelativePath 带目录前缀，供 createTasksWithDirectory 重建目录结构。
 * 必须在 drop 事件处理周期内同步传入 DataTransfer（事件循环后部分浏览器不再保证可读）。
 */
export async function readDataTransferFiles(
  dt: DataTransfer
): Promise<FileWithPath[]> {
  const entries = Array.from(dt.items)
    .map((item) => item.webkitGetAsEntry?.())
    .filter((entry): entry is FileSystemEntry => Boolean(entry))

  // 不支持 webkitGetAsEntry 时走传统 files 列表
  if (entries.length === 0) {
    return Array.from(dt.files) as FileWithPath[]
  }

  const files: FileWithPath[] = []
  await Promise.all(entries.map((entry) => readEntry(entry, '', files)))
  return files
}

/**
 * 判断拖拽事件是否携带系统文件，用于区分「从操作系统拖入上传」与
 * 「页面内拖拽移动」——后者只携带 application/json 自定义类型。
 */
export function hasExternalFiles(e: {
  dataTransfer: DataTransfer | null
}): boolean {
  return !!e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')
}
