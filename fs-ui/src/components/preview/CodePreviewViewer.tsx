import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import CodeMirrorEditor from './CodeMirrorEditor'
import { useTextFileContent } from './useTextFileContent'
import { PreviewTopBar } from './PreviewTopBar'
import type { PreviewViewerProps } from '@/utils/preview-types'

export function CodePreviewViewer({ files, index, startEdit }: PreviewViewerProps) {
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
      <CodeMirrorEditor
        file={file}
        value={content}
        loading={loading}
        readOnly={!editMode}
        dirty={dirty}
        saving={saving}
        onValueChange={setContent}
        onSave={() => void save()}
      />
    </div>
  )
}
