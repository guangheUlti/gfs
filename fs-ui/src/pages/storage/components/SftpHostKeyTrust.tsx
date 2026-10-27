import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { CheckCircle2, Loader2, ShieldCheck, ShieldAlert } from 'lucide-react'
import { probeStorageHostKey, trustStorageHostKey } from '@/api/storage'
import type { HostKeyInfo } from '@/types/storage'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'

interface SftpHostKeyTrustProps {
  platformIdentifier: string
  configData: Record<string, string>
}

/**
 * SFTP 主机密钥信任：探测指纹 → 人工核对 → 写入 known_hosts。
 * 只在 SFTP 平台表单内渲染。
 */
export function SftpHostKeyTrust({
  platformIdentifier,
  configData,
}: SftpHostKeyTrustProps) {
  const { t } = useTranslation('storage')
  const [probing, setProbing] = useState(false)
  const [trusting, setTrusting] = useState(false)
  const [pending, setPending] = useState<HostKeyInfo | null>(null)
  const [trusted, setTrusted] = useState<HostKeyInfo | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)

  const host = (configData.sftpHost || '').trim()
  const port = (configData.sftpPort || '22').trim()

  // 地址或端口改动后，之前的信任结果不再对应当前目标
  useEffect(() => {
    setTrusted(null)
    setPending(null)
    setDialogOpen(false)
  }, [host, port])

  const handleProbe = async () => {
    if (!host) return
    setProbing(true)
    try {
      const info = await probeStorageHostKey(
        platformIdentifier,
        JSON.stringify(configData)
      )
      if (info.trusted) {
        setTrusted(info)
        toast.success(t('hostKey.alreadyTrusted'))
        return
      }
      setPending(info)
      setDialogOpen(true)
    } catch {
      // 统一由 request 拦截器提示错误
    } finally {
      setProbing(false)
    }
  }

  const handleTrust = async () => {
    if (!pending) return
    setTrusting(true)
    try {
      const info = await trustStorageHostKey(
        platformIdentifier,
        JSON.stringify(configData),
        pending.fingerprint
      )
      setTrusted(info)
      setPending(null)
      setDialogOpen(false)
      toast.success(t('hostKey.trustOk', { path: info.knownHostsPath }))
    } catch {
      // 统一由 request 拦截器提示错误
    } finally {
      setTrusting(false)
    }
  }

  return (
    <div className='rounded-lg border p-3'>
      <div className='flex items-start justify-between gap-3'>
        <div className='min-w-0'>
          <Label className='flex cursor-default items-center gap-1.5'>
            <ShieldCheck className='h-4 w-4 text-muted-foreground' />
            {t('hostKey.title')}
          </Label>
          <p className='mt-0.5 text-xs text-muted-foreground'>
            {t('hostKey.desc')}
          </p>
        </div>
        <Button
          type='button'
          variant='outline'
          size='sm'
          className='shrink-0'
          disabled={!host || probing || trusting}
          onClick={handleProbe}
        >
          {probing ? (
            <Loader2 className='mr-1.5 h-3 w-3 animate-spin' />
          ) : (
            <ShieldCheck className='mr-1.5 h-3 w-3' />
          )}
          {probing ? t('hostKey.probing') : t('hostKey.probe')}
        </Button>
      </div>

      {trusted && (
        <p className='mt-2 flex items-start gap-1.5 text-xs text-green-600'>
          <CheckCircle2 className='mt-0.5 h-3 w-3 shrink-0' />
          <span className='break-all'>
            {t('hostKey.trustedHint', {
              fingerprint: trusted.fingerprint,
            })}
          </span>
        </p>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className='sm:max-w-lg'>
          <DialogHeader>
            <DialogTitle>{t('hostKey.confirmTitle')}</DialogTitle>
            <DialogDescription>{t('hostKey.confirmDesc')}</DialogDescription>
          </DialogHeader>

          {pending && (
            <div className='space-y-3 text-sm'>
              <div className='grid grid-cols-[auto_1fr] gap-x-3 gap-y-2'>
                <span className='text-muted-foreground'>
                  {t('hostKey.hostLabel')}
                </span>
                <span className='break-all'>
                  {pending.host}:{pending.port}
                </span>
                <span className='text-muted-foreground'>
                  {t('hostKey.typeLabel')}
                </span>
                <span>{pending.keyType}</span>
                <span className='text-muted-foreground'>
                  {t('hostKey.fingerprintLabel')}
                </span>
                <span className='break-all font-mono text-xs leading-5'>
                  {pending.fingerprint}
                </span>
                <span className='text-muted-foreground'>
                  {t('hostKey.pathLabel')}
                </span>
                <span className='break-all font-mono text-xs leading-5'>
                  {pending.knownHostsPath}
                </span>
              </div>

              <Alert>
                <ShieldAlert className='h-4 w-4' />
                <AlertDescription>{t('hostKey.warning')}</AlertDescription>
              </Alert>
            </div>
          )}

          <DialogFooter>
            <DialogClose asChild>
              <Button variant='outline' disabled={trusting}>
                {t('hostKey.cancel')}
              </Button>
            </DialogClose>
            <Button onClick={handleTrust} disabled={trusting}>
              {trusting ? (
                <Loader2 className='mr-1.5 h-4 w-4 animate-spin' />
              ) : null}
              {trusting ? t('hostKey.trusting') : t('hostKey.trust')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
