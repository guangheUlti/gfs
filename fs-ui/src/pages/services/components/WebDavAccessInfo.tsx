import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { copyToClipboardWithToast } from '@/utils/clipboard'

/** 只读模式放行的方法，与 DavAuthFilter.READ_METHODS 对齐 */
const READ_METHODS = ['OPTIONS', 'PROPFIND', 'GET', 'HEAD']

/** 可写模式额外声明的方法，与 DavAuthFilter.ALLOW 对齐 */
const WRITE_METHODS = ['PUT', 'POST', 'MKCOL', 'DELETE', 'MOVE', 'COPY', 'LOCK', 'UNLOCK']

/**
 * WebDAV 接入信息：访问地址（可复制）+ 当前只读/可写下实际放行的方法。
 * 方法清单与后端过滤器同源，管理员改「只读模式」后这里同步反映真实能力。
 */
export function WebDavAccessInfo({ readOnly }: { readOnly: boolean }) {
  const { t } = useTranslation('services')
  const [copied, setCopied] = useState(false)

  // 与 Web 端同源部署，协议路径固定在 /dav；外网访问时域名由浏览器地址推导
  const url = `${window.location.origin}/dav`
  const methods = readOnly ? READ_METHODS : [...READ_METHODS, ...WRITE_METHODS]

  const handleCopy = async () => {
    if (!(await copyToClipboardWithToast(url))) return
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1500)
  }

  return (
    <div className='space-y-3 rounded-md border border-dashed px-3 py-3'>
      <div className='space-y-1.5'>
        <Label>{t('form.accessUrl')}</Label>
        <div className='flex items-center gap-2'>
          <code className='min-w-0 flex-1 truncate rounded bg-muted px-2 py-1.5 font-mono text-xs'>
            {url}
          </code>
          <Button
            type='button'
            variant='ghost'
            size='icon'
            className='size-7 shrink-0'
            onClick={handleCopy}
            aria-label={t('form.copyAccessUrl')}
          >
            {copied ? <Check className='h-3.5 w-3.5' /> : <Copy className='h-3.5 w-3.5' />}
          </Button>
        </div>
        <p className='text-xs text-muted-foreground'>{t('form.accessUrlHint')}</p>
      </div>

      <div className='space-y-1.5 border-t border-dashed pt-3'>
        <Label>{t('form.methods')}</Label>
        <div className='flex flex-wrap gap-1'>
          {methods.map((method) => (
            <Badge
              key={method}
              variant='secondary'
              className='font-mono text-[10px] font-normal'
            >
              {method}
            </Badge>
          ))}
        </div>
        <p className='text-xs text-muted-foreground'>
          {readOnly ? t('form.methodsReadOnlyHint') : t('form.methodsWritableHint')}
        </p>
      </div>
    </div>
  )
}
