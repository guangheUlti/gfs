import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'
import { readTextContent, updateTextContent } from '@/api/file'
import type { FileItem } from '@/types/file'

interface TextEditorModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  file: FileItem | null
  onSuccess: () => void
}

export function TextEditorModal({
  open,
  onOpenChange,
  file,
  onSuccess,
}: TextEditorModalProps) {
  const { t } = useTranslation('files')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  // 打开时加载的内容快照，用来判断是否真的有修改
  const loadedContentRef = useRef('')

  useEffect(() => {
    if (!open || !file) return
    setLoading(true)
    setContent('')
    loadedContentRef.current = ''
    readTextContent(file.id)
      .then((text) => {
        loadedContentRef.current = text
        setContent(text)
      })
      .catch(() => {
        toast.error(t('textEditor.loadFail'))
        onOpenChange(false)
      })
      .finally(() => setLoading(false))
  }, [open, file, t, onOpenChange])

  if (!file) return null

  const dirty = content !== loadedContentRef.current

  const handleSave = async () => {
    setSaving(true)
    try {
      await updateTextContent(file.id, content)
      toast.success(t('textEditor.saveOk'))
      loadedContentRef.current = content
      onSuccess()
      onOpenChange(false)
    } catch {
      toast.error(t('textEditor.saveFail'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='flex h-[85vh] flex-col gap-4 sm:max-w-[860px]'>
        <DialogHeader>
          <DialogTitle className='flex items-center gap-2 truncate'>
            {file.displayName}
            {dirty && !loading && (
              <span className='text-muted-foreground text-sm font-normal'>
                {t('textEditor.unsaved')}
              </span>
            )}
          </DialogTitle>
          <DialogDescription>{t('textEditor.description')}</DialogDescription>
        </DialogHeader>
        {loading ? (
          <div className='flex flex-1 items-center justify-center'>
            <Loader2 className='h-6 w-6 animate-spin text-muted-foreground' />
          </div>
        ) : (
          <Textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            className='flex-1 resize-none font-mono text-sm'
            spellCheck={false}
            autoFocus
          />
        )}
        <DialogFooter>
          <Button variant='outline' onClick={() => onOpenChange(false)}>
            {t('textEditor.cancel')}
          </Button>
          <Button onClick={handleSave} disabled={loading || saving || !dirty}>
            {saving && <Loader2 className='h-4 w-4 animate-spin' />}
            {t('textEditor.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
