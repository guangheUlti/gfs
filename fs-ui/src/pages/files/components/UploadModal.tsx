import { useState, useEffect, useCallback, useRef, memo } from 'react'
import { useTranslation } from 'react-i18next'
import { readDataTransferFiles, type FileWithPath } from '@/utils/data-transfer'
import { useTransferStore } from '@/store/transfer'
import { Upload, X, FileIcon, FolderUp } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'

interface UploadModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  parentId?: string
}

// 单个文件 item，memo 避免无关重渲染
const FileItem = memo(
  ({
    index,
    displayName,
    onRemove,
  }: {
    file: FileWithPath
    index: number
    displayName: string
    onRemove: (index: number) => void
  }) => (
    <div className='flex animate-in items-center justify-between rounded-lg bg-accent p-3 fade-in slide-in-from-top-1'>
      <div className='flex min-w-0 flex-1 items-center gap-2'>
        <FileIcon className='h-4 w-4 flex-shrink-0' />
        <Tooltip>
          <TooltipTrigger asChild>
            <span
              className='text-sm break-words whitespace-normal'
              style={{ wordBreak: 'break-all' }}
            >
              {displayName}
            </span>
          </TooltipTrigger>
          <TooltipContent>
            <p>{displayName}</p>
          </TooltipContent>
        </Tooltip>
      </div>
      <X
        className='h-4 w-4 flex-shrink-0 cursor-pointer text-muted-foreground hover:text-destructive'
        onClick={() => onRemove(index)}
      />
    </div>
  )
)

export default function UploadModal({
  open,
  onOpenChange,
  parentId,
}: UploadModalProps) {
  const { t } = useTranslation('files')
  const [fileList, setFileList] = useState<FileWithPath[]>([])
  const [isDragging, setIsDragging] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)

  const { startUploadSession, createTasksWithDirectory } = useTransferStore()

  useEffect(() => {
    if (!open) {
      setFileList([])
    }
  }, [open])

  const appendFiles = useCallback((files: FileWithPath[]) => {
    if (files.length === 0) return
    setFileList((prev) => [...prev, ...files])
  }, [])

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    appendFiles(Array.from(e.target.files || []) as FileWithPath[])
    // 清空 value，重复选择同一批文件也能再次触发 onChange
    e.target.value = ''
  }

  const handleRemoveFile = useCallback((index: number) => {
    setFileList((prev) => prev.filter((_, i) => i !== index))
  }, [])

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(true)
  }, [])

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
  }, [])

  const handleDrop = useCallback(
    async (e: React.DragEvent) => {
      e.preventDefault()
      setIsDragging(false)

      const files = await readDataTransferFiles(e.dataTransfer)
      appendFiles(files)
    },
    [appendFiles]
  )

  const handleSubmit = async () => {
    if (fileList.length === 0) {
      toast.warning(t('upload.pickFile'))
      return
    }

    startUploadSession()
    try {
      // 混合输入安全：无相对路径的文件落到当前目录，带路径的按目录结构创建
      await createTasksWithDirectory(fileList, parentId)
    } catch {
      // 大小/深度等超限由 transfer store 内部 toast 提示，弹窗保持打开
      return
    }

    onOpenChange(false)
    toast.success(t('operations.uploadFileAdded'), {
      description: t('operations.uploadCheckProgress'),
    })
  }

  // 拖入的文件夹显示完整相对路径，顶层文件只显示文件名
  const getDisplayName = (file: FileWithPath) =>
    file.webkitRelativePath && file.webkitRelativePath !== file.name
      ? file.webkitRelativePath
      : file.name

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-w-xl'>
        <DialogHeader>
          <DialogTitle>{t('upload.titleFile')}</DialogTitle>
        </DialogHeader>

        <div className='py-4'>
          {/* 浏览器限制一个选择器无法同时选文件和文件夹，用两个 input 分别触发 */}
          <input
            ref={fileInputRef}
            type='file'
            multiple
            onChange={handleFileChange}
            className='hidden'
          />
          <input
            ref={folderInputRef}
            type='file'
            {...{ webkitdirectory: '', directory: '' }}
            onChange={handleFileChange}
            className='hidden'
          />

          <div
            className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors ${
              isDragging
                ? 'border-primary bg-primary/5'
                : 'border-border hover:border-primary hover:bg-accent'
            }`}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
          >
            <Upload className='mb-4 h-12 w-12 text-primary' />
            <div className='text-base font-medium text-foreground'>
              {t('upload.dropFile')}
            </div>
            <div className='mt-2 text-sm text-muted-foreground'>
              {t('upload.hintFile')}
            </div>
            {/* 阻止冒泡：按钮自己触发对应选择器，不落到整区点击 */}
            <div
              className='mt-4 flex gap-2'
              onClick={(e) => e.stopPropagation()}
            >
              <Button
                type='button'
                size='sm'
                onClick={() => fileInputRef.current?.click()}
              >
                <Upload className='h-4 w-4' />
                {t('upload.chooseFile')}
              </Button>
              <Button
                type='button'
                size='sm'
                variant='outline'
                onClick={() => folderInputRef.current?.click()}
              >
                <FolderUp className='h-4 w-4' />
                {t('upload.chooseFolder')}
              </Button>
            </div>
          </div>

          {fileList.length > 0 && (
            <TooltipProvider>
              <div className='mt-4 max-h-60 space-y-2 overflow-y-auto'>
                {fileList.map((file, index) => (
                  <FileItem
                    key={index}
                    file={file}
                    index={index}
                    displayName={getDisplayName(file)}
                    onRemove={handleRemoveFile}
                  />
                ))}
              </div>
            </TooltipProvider>
          )}
        </div>

        <DialogFooter>
          <Button variant='outline' onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={fileList.length === 0}>
            {t('upload.addToList')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
