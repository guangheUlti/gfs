import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { Loader2 } from 'lucide-react'
import CodeMirrorEditor from './CodeMirrorEditor'
import { useTextFileContent } from './useTextFileContent'
import { PreviewTopBar } from './PreviewTopBar'
import type { PreviewViewerProps } from '@/utils/preview-types'

export function MarkdownPreviewViewer({ files, index, startEdit }: PreviewViewerProps) {
  const { t } = useTranslation('files')
  const file = files[index]
  const { content, setContent, loading, saving, dirty, save } = useTextFileContent(file)
  const [editMode, setEditMode] = useState(startEdit === true)

  return (
    <div className='flex h-full flex-col'>
      <PreviewTopBar title={file.displayName} dirty={dirty}>
        <button
          type='button'
          onClick={() => setEditMode((prev) => !prev)}
          className='cursor-pointer text-sm opacity-90 transition-opacity hover:opacity-60'
        >
          {editMode ? t('preview.modePreview') : t('preview.modeEdit')}
        </button>
      </PreviewTopBar>

      {editMode ? (
        <CodeMirrorEditor
          file={file}
          value={content}
          loading={loading}
          readOnly={false}
          dirty={dirty}
          saving={saving}
          onValueChange={setContent}
          onSave={() => void save()}
        />
      ) : loading ? (
        <div className='flex flex-1 items-center justify-center text-muted-foreground'>
          <Loader2 className='h-8 w-8 animate-spin' />
        </div>
      ) : (
        <div className='min-h-0 flex-1 overflow-auto bg-background px-8 py-6'>
          <div className='markdown-preview mx-auto max-w-4xl'>
            <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>
              {content}
            </Markdown>
          </div>
        </div>
      )}
    </div>
  )
}
