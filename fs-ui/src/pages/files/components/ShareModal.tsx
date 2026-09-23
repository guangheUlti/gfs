import { useState, useEffect, useMemo, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { shareFiles } from '@/api'
import type { FileItem } from '@/types/file'
import { Copy, Check } from 'lucide-react'
import { toast } from 'sonner'
import { formatFileSize } from '@/utils/format'
import { copyToClipboard } from '@/utils/clipboard'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Separator } from '@/components/ui/separator'
import { FileIcon } from '@/components/file-icon'
import {
  FormFieldStack,
  FormInlineOption,
} from '@/components/field-layout'

interface ShareModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  file: FileItem | null
  files: FileItem[]
  onSuccess?: () => void
}

export function ShareModal({
  open,
  onOpenChange,
  file,
  files,
  onSuccess,
}: ShareModalProps) {
  const { t } = useTranslation('files')
  // 表单状态
  const [expireType, setExpireType] = useState<number>(1) // 1-7天 2-30天 3-自定义 4-永久
  const [customExpireTime, setCustomExpireTime] = useState<string>('')
  const [needShareCode, setNeedShareCode] = useState(false)
  const [maxViewCountType, setMaxViewCountType] = useState<
    'unlimited' | 'custom'
  >('unlimited')
  const [maxViewCount, setMaxViewCount] = useState<string>('')
  const [maxDownloadCountType, setMaxDownloadCountType] = useState<
    'unlimited' | 'custom'
  >('unlimited')
  const [maxDownloadCount, setMaxDownloadCount] = useState<string>('')

  // 分享结果状态
  const [shareLink, setShareLink] = useState('')
  const [shareCode, setShareCode] = useState('')
  const [shareExpireTime, setShareExpireTime] = useState('')
  const [isPermanent, setIsPermanent] = useState(false)

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [copiedLink, setCopiedLink] = useState(false)
  const [rawLink, setRawLink] = useState('')
  const [copiedRawLink, setCopiedRawLink] = useState(false)

  const sharingFiles = file ? [file] : files
  const isBatchShare = sharingFiles.length > 1
  const displayFiles = sharingFiles.slice(0, 3)
  // 直链只对单文件分享展示；文件夹在分享页内逐个下载
  const singleFile =
    sharingFiles.length === 1 && !sharingFiles[0].isDir
      ? sharingFiles[0]
      : null

  // 判断是否永久分享
  const isPermanentShare = () => {
    if (shareLink) {
      return isPermanent
    }
    return expireType === 4
  }

  // 文件数量提示文本
  const filesCountText = useMemo(() => {
    const total = sharingFiles.length
    if (total > 3) {
      return t('shareModal.filesMore', { total })
    }
    return t('shareModal.filesTotal', { total })
  }, [sharingFiles.length, t])

  // 重置表单
  const resetForm = () => {
    setExpireType(1)
    setCustomExpireTime('')
    setNeedShareCode(false)
    setMaxViewCountType('unlimited')
    setMaxViewCount('')
    setMaxDownloadCountType('unlimited')
    setMaxDownloadCount('')
    setShareLink('')
    setShareCode('')
    setShareExpireTime('')
    setIsPermanent(false)
    setRawLink('')
  }

  // 获取过期时间文本
  const getExpireText = useCallback(() => {
    if (shareExpireTime) {
      return shareExpireTime
    }
    if (expireType === 4) return t('shareModal.permanent')
    if (expireType === 3 && customExpireTime) {
      return customExpireTime
    }
    if (expireType === 1) return t('shareModal.expire7')
    if (expireType === 2) return t('shareModal.expire30')
    return ''
  }, [shareExpireTime, expireType, customExpireTime, t])

  // 生成分享链接
  const handleShare = async () => {
    // 验证自定义时间
    if (expireType === 3 && !customExpireTime) {
      toast.warning(t('shareModal.toastPickExpire'))
      return
    }

    // 验证自定义次数
    if (maxViewCountType === 'custom' && !maxViewCount) {
      toast.warning(t('shareModal.toastMaxView'))
      return
    }

    if (maxDownloadCountType === 'custom' && !maxDownloadCount) {
      toast.warning(t('shareModal.toastMaxDown'))
      return
    }

    setIsSubmitting(true)
    try {
      const fileIds = sharingFiles.map((f) => f.id)

      const response = await shareFiles({
        fileIds,
        expireType,
        expireTime: expireType === 3 ? customExpireTime : undefined,
        needShareCode,
        maxViewCount:
          maxViewCountType === 'custom' ? Number(maxViewCount) : undefined,
        maxDownloadCount:
          maxDownloadCountType === 'custom'
            ? Number(maxDownloadCount)
            : undefined,
      })

      if (response) {
        const shareToken = response.id
        const baseUrl = window.location.origin
        setShareLink(`${baseUrl}/s/${shareToken}`)
        setShareCode(response.shareCode || '')
        setShareExpireTime(response.expireTime || '')
        setIsPermanent(response.isPermanent)
        setRawLink(
          singleFile
            ? `${baseUrl}/apis/share/${response.id}/raw/${singleFile.id}`
            : ''
        )

        const successMsg =
          fileIds.length === 1
            ? t('shareModal.toastOkOne')
            : t('shareModal.toastOkMany', { count: fileIds.length })
        toast.success(successMsg)
        onSuccess?.()
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  // 复制链接
  const handleCopyLink = async () => {
    const textToCopy = shareCode
      ? t('shareModal.copyWithCode', { link: shareLink, code: shareCode })
      : shareLink

    const ok = await copyToClipboard(textToCopy)
    if (ok) {
      setCopiedLink(true)
      setTimeout(() => {
        setCopiedLink(false)
      }, 2000)
      toast.success(t('common.copied'))
    } else {
      toast.error(t('common.copyFailed'))
    }
  }

  // 复制直链
  const handleCopyRawLink = async () => {
    const ok = await copyToClipboard(rawLink)
    if (ok) {
      setCopiedRawLink(true)
      setTimeout(() => {
        setCopiedRawLink(false)
      }, 2000)
      toast.success(t('common.copied'))
    } else {
      toast.error(t('common.copyFailed'))
    }
  }

  // 处理确认按钮点击
  const handleOk = async () => {
    if (!shareLink) {
      await handleShare()
    }
  }

  // 关闭时重置
  useEffect(() => {
    if (!open) {
      setTimeout(() => {
        resetForm()
        setCopiedLink(false)
        setCopiedRawLink(false)
      }, 300)
    }
  }, [open])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[85vh] overflow-y-auto overflow-x-hidden sm:max-w-xl'>
        <DialogHeader>
          <DialogTitle>{t('shareModal.title')}</DialogTitle>
        </DialogHeader>

        <div className='space-y-6'>
          {/* 文件信息预览 */}
          {!isBatchShare && file ? (
            <div className='flex items-center gap-4 rounded-lg bg-muted/50 p-4'>
              <FileIcon
                type={file.isDir ? 'dir' : file.suffix || ''}
                size={48}
              />
              <div className='min-w-0 flex-1'>
                <div className='truncate text-sm font-medium'>
                  {file.displayName}
                </div>
                <div className='text-xs text-muted-foreground'>
                  {formatFileSize(file.size || 0)}
                </div>
              </div>
            </div>
          ) : (
            <div className='space-y-3'>
              <div className='flex justify-center gap-6 rounded-lg bg-muted/30 p-6'>
                {displayFiles.map((previewFile) => (
                  <div
                    key={previewFile.id}
                    className='flex w-20 flex-col items-center gap-2'
                  >
                    <FileIcon
                      type={
                        previewFile.isDir ? 'dir' : previewFile.suffix || ''
                      }
                      size={64}
                    />
                    <span className='w-full truncate text-center text-xs text-muted-foreground'>
                      {previewFile.displayName}
                    </span>
                  </div>
                ))}
              </div>
              <div className='text-center text-sm text-muted-foreground'>
                {filesCountText}
              </div>
            </div>
          )}

          <Separator />

          {!shareLink ? (
            <>
              {/* 有效期 */}
              <FormFieldStack>
                <Label>{t('shareModal.labelExpire')}</Label>
                <RadioGroup
                  value={String(expireType)}
                  onValueChange={(value) => setExpireType(Number(value))}
                  disabled={!!shareLink}
                  className='flex flex-wrap gap-x-6 gap-y-2'
                >
                  <FormInlineOption>
                    <RadioGroupItem value='1' id='expire-7d' />
                    <Label
                      htmlFor='expire-7d'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.expire7')}
                    </Label>
                  </FormInlineOption>
                  <FormInlineOption>
                    <RadioGroupItem value='2' id='expire-30d' />
                    <Label
                      htmlFor='expire-30d'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.expire30')}
                    </Label>
                  </FormInlineOption>
                  <FormInlineOption>
                    <RadioGroupItem value='3' id='expire-custom' />
                    <Label
                      htmlFor='expire-custom'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.expireCustom')}
                    </Label>
                  </FormInlineOption>
                  <FormInlineOption>
                    <RadioGroupItem value='4' id='expire-permanent' />
                    <Label
                      htmlFor='expire-permanent'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.permanent')}
                    </Label>
                  </FormInlineOption>
                </RadioGroup>
                {expireType === 3 && (
                  <Input
                    type='datetime-local'
                    value={customExpireTime}
                    onChange={(e) => setCustomExpireTime(e.target.value)}
                    disabled={!!shareLink}
                    min={new Date().toISOString().slice(0, 16)}
                  />
                )}
              </FormFieldStack>

              {/* 分享类型 */}
              <FormFieldStack>
                <Label>{t('shareModal.labelShareType')}</Label>
                <RadioGroup
                  value={needShareCode ? 'private' : 'public'}
                  onValueChange={(value) =>
                    setNeedShareCode(value === 'private')
                  }
                  disabled={!!shareLink}
                  className='flex flex-wrap gap-x-6 gap-y-2'
                >
                  <FormInlineOption>
                    <RadioGroupItem value='public' id='share-public' />
                    <Label
                      htmlFor='share-public'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.sharePublic')}
                    </Label>
                  </FormInlineOption>
                  <FormInlineOption>
                    <RadioGroupItem value='private' id='share-private' />
                    <Label
                      htmlFor='share-private'
                      className='cursor-pointer font-normal'
                    >
                      {t('shareModal.sharePrivate')}
                    </Label>
                  </FormInlineOption>
                </RadioGroup>
              </FormFieldStack>

              {/* 最大查看次数 */}
              <FormFieldStack>
                <Label>{t('shareModal.labelMaxView')}</Label>
                <div className='flex flex-wrap items-center gap-2'>
                  <RadioGroup
                    value={maxViewCountType}
                    onValueChange={(value) =>
                      setMaxViewCountType(value as 'unlimited' | 'custom')
                    }
                    disabled={!!shareLink}
                    className='flex flex-wrap gap-x-6 gap-y-2'
                  >
                    <FormInlineOption>
                      <RadioGroupItem value='unlimited' id='view-unlimited' />
                      <Label
                        htmlFor='view-unlimited'
                        className='cursor-pointer font-normal'
                      >
                        {t('shareModal.unlimited')}
                      </Label>
                    </FormInlineOption>
                    <FormInlineOption>
                      <RadioGroupItem value='custom' id='view-custom' />
                      <Label
                        htmlFor='view-custom'
                        className='cursor-pointer font-normal'
                      >
                        {t('shareModal.custom')}
                      </Label>
                    </FormInlineOption>
                  </RadioGroup>
                  {maxViewCountType === 'custom' && (
                    <Input
                      type='number'
                      min='1'
                      placeholder={t('shareModal.placeholderCount')}
                      value={maxViewCount}
                      onChange={(e) => setMaxViewCount(e.target.value)}
                      disabled={!!shareLink}
                      className='w-32'
                    />
                  )}
                </div>
              </FormFieldStack>

              {/* 最大下载次数 */}
              <FormFieldStack>
                <Label>{t('shareModal.labelMaxDown')}</Label>
                <div className='flex flex-wrap items-center gap-2'>
                  <RadioGroup
                    value={maxDownloadCountType}
                    onValueChange={(value) =>
                      setMaxDownloadCountType(value as 'unlimited' | 'custom')
                    }
                    disabled={!!shareLink}
                    className='flex flex-wrap gap-x-6 gap-y-2'
                  >
                    <FormInlineOption>
                      <RadioGroupItem
                        value='unlimited'
                        id='download-unlimited'
                      />
                      <Label
                        htmlFor='download-unlimited'
                        className='cursor-pointer font-normal'
                      >
                        {t('shareModal.unlimited')}
                      </Label>
                    </FormInlineOption>
                    <FormInlineOption>
                      <RadioGroupItem value='custom' id='download-custom' />
                      <Label
                        htmlFor='download-custom'
                        className='cursor-pointer font-normal'
                      >
                        {t('shareModal.custom')}
                      </Label>
                    </FormInlineOption>
                  </RadioGroup>
                  {maxDownloadCountType === 'custom' && (
                    <Input
                      type='number'
                      min='1'
                      placeholder={t('shareModal.placeholderCount')}
                      value={maxDownloadCount}
                      onChange={(e) => setMaxDownloadCount(e.target.value)}
                      disabled={!!shareLink}
                      className='w-32'
                    />
                  )}
                </div>
              </FormFieldStack>
            </>
          ) : (
            <>
              {/* 分享结果 */}
              <Alert className='border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950'>
                <Check className='h-4 w-4 text-green-600 dark:text-green-400' />
                <AlertDescription className='text-green-600 dark:text-green-400'>
                  {t('shareModal.alertGenerated')}
                </AlertDescription>
              </Alert>

              <div className='space-y-3 rounded-lg bg-muted/100 p-4'>
                {/* 分享页链接和提取码：链接长度自适应（截断+title 悬停看全），右侧「分享页链接」按钮复制 */}
                <div className='flex items-center gap-2'>
                  <div
                    className='min-w-0 flex-1 truncate text-sm'
                    title={shareCode ? `${shareLink} ${t('shareModal.codeInline')}${shareCode}` : shareLink}
                  >
                    {shareLink}
                    {shareCode && (
                      <span className='ml-2 text-muted-foreground'>
                        {t('shareModal.codeInline')}
                        {shareCode}
                      </span>
                    )}
                  </div>
                  <Button variant='outline' size='sm' onClick={handleCopyLink}>
                    {copiedLink ? (
                      <>
                        <Check className='mr-2 h-4 w-4' />
                        {t('shareModal.btnCopied')}
                      </>
                    ) : (
                      <>
                        <Copy className='mr-2 h-4 w-4' />
                        {t('shareModal.btnCopySharePage')}
                      </>
                    )}
                  </Button>
                </div>
              </div>

              {rawLink && (
                <div className='space-y-2 rounded-lg border p-4'>
                  <div className='text-xs text-muted-foreground'>
                    {t('shareModal.rawLinkTip')}
                  </div>
                  <div className='flex items-center gap-2'>
                    <div className='min-w-0 flex-1 truncate text-sm' title={rawLink}>
                      {rawLink}
                    </div>
                    <Button variant='outline' size='sm' onClick={handleCopyRawLink}>
                      {copiedRawLink ? (
                        <>
                          <Check className='mr-2 h-4 w-4' />
                          {t('shareModal.btnCopied')}
                        </>
                      ) : (
                        <>
                          <Copy className='mr-2 h-4 w-4' />
                          {t('shareModal.btnCopyRaw')}
                        </>
                      )}
                    </Button>
                  </div>
                </div>
              )}

              <div className='text-center text-sm text-muted-foreground'>
                {isPermanentShare()
                  ? t('shareModal.hintPermanent')
                  : t('shareModal.hintExpire', { time: getExpireText() })}
              </div>
            </>
          )}
        </div>

        {/* 生成结果状态下不渲染底部按钮：复制操作已内联到两条链接行，关闭走右上角 X */}
        {!shareLink && (
          <DialogFooter>
            <DialogClose asChild>
              <Button variant='outline' disabled={isSubmitting}>
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button onClick={handleOk} disabled={isSubmitting}>
              {isSubmitting
                ? t('shareModal.generating')
                : t('shareModal.generate')}
            </Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
