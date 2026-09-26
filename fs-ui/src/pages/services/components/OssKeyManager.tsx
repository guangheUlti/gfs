import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, KeyRound, Plus, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import {
  createOssAccessKey,
  listOssAccessKeys,
  revokeOssAccessKey,
  type OssAccessKeyCreatedVO,
} from '@/api/service'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { ConfirmDialog } from '@/components/confirm-dialog'
import { Label } from '@/components/ui/label'

/** 查询缓存 key（创建/吊销后失效） */
export const OSS_KEYS_QUERY_KEY = ['ossAccessKeys']

interface OssKeyManagerProps {
  /** 服务是否启用；停用时禁用新建入口 */
  serviceEnabled: boolean
}

/**
 * OSS 访问密钥管理块：SigV4 密钥对（AK/SK）列表 + 签发 + 吊销。
 * Secret Key 明文仅在创建弹窗返回一次，关闭后不可再见。
 */
export function OssKeyManager({ serviceEnabled }: OssKeyManagerProps) {
  const { t } = useTranslation('services')
  const queryClient = useQueryClient()

  const [createOpen, setCreateOpen] = useState(false)
  const [remark, setRemark] = useState('')
  const [created, setCreated] = useState<OssAccessKeyCreatedVO | null>(null)
  const [revokeTarget, setRevokeTarget] = useState<string | null>(null)

  const { data: keys = [], isLoading } = useQuery({
    queryKey: OSS_KEYS_QUERY_KEY,
    queryFn: listOssAccessKeys,
  })

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: OSS_KEYS_QUERY_KEY })

  const createMutation = useMutation({
    mutationFn: () => createOssAccessKey(remark.trim()),
    onSuccess: (vo) => {
      setCreateOpen(false)
      setRemark('')
      setCreated(vo)
      invalidate()
    },
  })

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeOssAccessKey(id),
    onSuccess: () => {
      toast.success(t('oss.keys.revokeOk'))
      setRevokeTarget(null)
      invalidate()
    },
  })

  const copy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(label)
    } catch {
      toast.error(t('oss.keys.copyFailed'))
    }
  }

  return (
    <div className='border-t bg-muted/40 px-5 py-4'>
      <div className='flex items-center justify-between'>
        <div className='flex items-center gap-2'>
          <KeyRound className='h-4 w-4 text-muted-foreground' />
          <h4 className='text-sm font-medium'>{t('oss.keys.title')}</h4>
        </div>
        <Button
          size='sm'
          variant='outline'
          disabled={!serviceEnabled || createMutation.isPending}
          onClick={() => setCreateOpen(true)}
        >
          <Plus className='mr-1 h-4 w-4' />
          {t('oss.keys.create')}
        </Button>
      </div>

      {isLoading ? (
        <p className='mt-3 text-xs text-muted-foreground'>{t('oss.keys.loading')}</p>
      ) : keys.length === 0 ? (
        <p className='mt-3 text-xs text-muted-foreground'>{t('oss.keys.empty')}</p>
      ) : (
        <ul className='mt-3 space-y-2'>
          {keys.map((key) => (
            <li
              key={key.id}
              className='flex items-center justify-between gap-2 rounded-md border bg-background px-3 py-2'
            >
              <div className='min-w-0'>
                <p className='truncate font-mono text-xs'>
                  {key.accessKeyMasked}
                  {key.status === 1 && (
                    <span className='ml-2 rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] text-destructive'>
                      {t('oss.keys.revoked')}
                    </span>
                  )}
                </p>
                <p className='truncate text-[11px] text-muted-foreground'>
                  {key.remark || t('oss.keys.noRemark')}
                  {key.lastUsedAt && ` · ${t('oss.keys.lastUsed')}: ${key.lastUsedAt}`}
                </p>
              </div>
              {key.status === 0 && (
                <Button
                  size='icon'
                  variant='ghost'
                  className='h-7 w-7 shrink-0 text-destructive'
                  disabled={revokeMutation.isPending}
                  onClick={() => setRevokeTarget(key.id)}
                >
                  <Trash2 className='h-3.5 w-3.5' />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/* 创建弹窗：填写备注 */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className='sm:max-w-md'>
          <DialogHeader>
            <DialogTitle>{t('oss.keys.createTitle')}</DialogTitle>
            <DialogDescription>{t('oss.keys.createDesc')}</DialogDescription>
          </DialogHeader>
          <div className='space-y-1.5'>
            <Label htmlFor='oss-key-remark'>{t('oss.keys.remarkLabel')}</Label>
            <Input
              id='oss-key-remark'
              placeholder={t('oss.keys.remarkPh')}
              value={remark}
              maxLength={100}
              onChange={(e) => setRemark(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setCreateOpen(false)}>
              {t('oss.keys.cancel')}
            </Button>
            <Button
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate()}
            >
              {createMutation.isPending ? t('oss.keys.creating') : t('oss.keys.create')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 创建成功：明文 SK 仅此一次展示 */}
      <Dialog open={created != null} onOpenChange={(open) => !open && setCreated(null)}>
        <DialogContent className='sm:max-w-lg'>
          <DialogHeader>
            <DialogTitle>{t('oss.keys.createdTitle')}</DialogTitle>
            <DialogDescription>{t('oss.keys.createdDesc')}</DialogDescription>
          </DialogHeader>
          {created && (
            <div className='space-y-3'>
              <div className='space-y-1'>
                <Label>{t('oss.keys.accessKeyLabel')}</Label>
                <div className='flex items-center gap-2'>
                  <code className='min-w-0 flex-1 truncate rounded bg-muted px-2 py-1.5 font-mono text-xs'>
                    {created.accessKey}
                  </code>
                  <Button
                    size='icon'
                    variant='outline'
                    className='h-8 w-8 shrink-0'
                    onClick={() => copy(created.accessKey, t('oss.keys.copiedAk'))}
                  >
                    <Copy className='h-3.5 w-3.5' />
                  </Button>
                </div>
              </div>
              <div className='space-y-1'>
                <Label>{t('oss.keys.secretKeyLabel')}</Label>
                <div className='flex items-center gap-2'>
                  <code className='min-w-0 flex-1 truncate rounded bg-muted px-2 py-1.5 font-mono text-xs'>
                    {created.secretKey}
                  </code>
                  <Button
                    size='icon'
                    variant='outline'
                    className='h-8 w-8 shrink-0'
                    onClick={() => copy(created.secretKey, t('oss.keys.copiedSk'))}
                  >
                    <Copy className='h-3.5 w-3.5' />
                  </Button>
                </div>
              </div>
            </div>
          )}
          <DialogFooter>
            <Button onClick={() => setCreated(null)}>{t('oss.keys.done')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 吊销确认 */}
      <ConfirmDialog
        open={revokeTarget != null}
        onOpenChange={(open) => !open && setRevokeTarget(null)}
        title={t('oss.keys.revokeTitle')}
        desc={t('oss.keys.revokeDesc')}
        confirmText={t('oss.keys.revoke')}
        isLoading={revokeMutation.isPending}
        handleConfirm={() => revokeTarget && revokeMutation.mutate(revokeTarget)}
      />
    </div>
  )
}
