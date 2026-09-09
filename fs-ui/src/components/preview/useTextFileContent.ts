import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { readTextContent, updateTextContent } from '@/api/file'
import type { FileItem } from '@/types/file'

/**
 * 文本文件内容编辑 hook：加载/脏标记/保存，供代码与 Markdown 预览共用
 */
export function useTextFileContent(file: FileItem) {
  const { t } = useTranslation('files')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const loadedRef = useRef('')

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setContent('')
    loadedRef.current = ''
    readTextContent(file.id)
      .then((text) => {
        if (cancelled) return
        loadedRef.current = text
        setContent(text)
      })
      .catch(() => {
        if (!cancelled) toast.error(t('preview.loadFail'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [file.id, t])

  const save = useCallback(async () => {
    if (saving || content === loadedRef.current) return
    setSaving(true)
    try {
      await updateTextContent(file.id, content)
      toast.success(t('preview.saveOk'))
      loadedRef.current = content
    } catch {
      toast.error(t('preview.saveFail'))
    } finally {
      setSaving(false)
    }
  }, [content, saving, file.id, t])

  return {
    content,
    setContent,
    loading,
    saving,
    dirty: !loading && content !== loadedRef.current,
    save,
  }
}
