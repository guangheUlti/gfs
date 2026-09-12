import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Save } from 'lucide-react'
import { keymap, EditorView } from '@codemirror/view'
import {
  HighlightStyle,
  syntaxHighlighting,
  LanguageDescription,
  type LanguageSupport,
} from '@codemirror/language'
import { tags } from '@lezer/highlight'
import type { Extension } from '@codemirror/state'
import { languages } from '@codemirror/language-data'
import {
  abcdef, abyss, androidstudio, andromeda, atomone, aura, basicDark, basicLight,
  bbedit, bespin, consoleDark, consoleLight, copilot, darcula, dracula,
  duotoneLight, duotoneDark, eclipse, githubLight, githubDark, gruvboxDark,
  gruvboxLight, kimbie, materialDark, material, materialLight, monokai,
  monokaiDimmed, noctisLilac, nord, okaidia, quietlight, red, solarizedDark,
  solarizedLight, sublime, tokyoNight, tokyoNightDay, tokyoNightStorm,
  tomorrowNightBlue, vscodeDark, vscodeLight, whiteDark, whiteLight,
  xcodeLight, xcodeDark,
} from '@uiw/codemirror-themes-all'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import type { FileItem } from '@/types/file'

const CodeMirror = lazy(() => import('@uiw/react-codemirror'))

const THEME_KEY = 'gfs.codemirror-theme'
const FONT_SIZE_KEY = 'gfs.codemirror-font-size'
const WRAP_KEY = 'gfs.codemirror-wrap'
const FONT_SIZES = Array.from({ length: 19 }, (_, i) => i + 12)

const THEME_LIST: { name: string; value: Extension }[] = [
  { name: 'abcdef', value: abcdef },
  { name: 'abyss', value: abyss },
  { name: 'androidstudio', value: androidstudio },
  { name: 'andromeda', value: andromeda },
  { name: 'atomone', value: atomone },
  { name: 'aura', value: aura },
  { name: 'basicDark', value: basicDark },
  { name: 'basicLight', value: basicLight },
  { name: 'bbedit', value: bbedit },
  { name: 'bespin', value: bespin },
  { name: 'consoleDark', value: consoleDark },
  { name: 'consoleLight', value: consoleLight },
  { name: 'copilot', value: copilot },
  { name: 'darcula', value: darcula },
  { name: 'dracula', value: dracula },
  { name: 'duotoneDark', value: duotoneDark },
  { name: 'duotoneLight', value: duotoneLight },
  { name: 'eclipse', value: eclipse },
  { name: 'githubDark', value: githubDark },
  { name: 'githubLight', value: githubLight },
  { name: 'gruvboxDark', value: gruvboxDark },
  { name: 'gruvboxLight', value: gruvboxLight },
  { name: 'kimbie', value: kimbie },
  { name: 'material', value: material },
  { name: 'materialDark', value: materialDark },
  { name: 'materialLight', value: materialLight },
  { name: 'monokai', value: monokai },
  { name: 'monokaiDimmed', value: monokaiDimmed },
  { name: 'noctisLilac', value: noctisLilac },
  { name: 'nord', value: nord },
  { name: 'okaidia', value: okaidia },
  { name: 'quietlight', value: quietlight },
  { name: 'red', value: red },
  { name: 'solarizedDark', value: solarizedDark },
  { name: 'solarizedLight', value: solarizedLight },
  { name: 'sublime', value: sublime },
  { name: 'tokyoNight', value: tokyoNight },
  { name: 'tokyoNightDay', value: tokyoNightDay },
  { name: 'tokyoNightStorm', value: tokyoNightStorm },
  { name: 'tomorrowNightBlue', value: tomorrowNightBlue },
  { name: 'vscodeDark', value: vscodeDark },
  { name: 'vscodeLight', value: vscodeLight },
  { name: 'whiteDark', value: whiteDark },
  { name: 'whiteLight', value: whiteLight },
  { name: 'xcodeDark', value: xcodeDark },
  { name: 'xcodeLight', value: xcodeLight },
]

interface CodeMirrorEditorProps {
  file: FileItem
  value: string
  loading: boolean
  readOnly: boolean
  dirty: boolean
  saving: boolean
  onValueChange: (value: string) => void
  onSave: () => void
}

export default function CodeMirrorEditor({
  file,
  value,
  loading,
  readOnly,
  dirty,
  saving,
  onValueChange,
  onSave,
}: CodeMirrorEditorProps) {
  const { t } = useTranslation('files')
  const [wrap, setWrap] = useState(() => localStorage.getItem(WRAP_KEY) !== 'false')
  const [fontSize, setFontSize] = useState(
    () => Number(localStorage.getItem(FONT_SIZE_KEY)) || 14
  )
  const [themeName, setThemeName] = useState(() => {
    const saved = localStorage.getItem(THEME_KEY)
    return saved && saved !== 'default' ? saved : 'system'
  })
  const containerRef = useRef<HTMLDivElement>(null)

  // Ctrl+滚轮默认缩放编辑器字号（拦截浏览器整页缩放），与工具栏字号选择联动
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return
      e.preventDefault()
      setFontSize((size) => {
        const next = Math.min(
          FONT_SIZES[FONT_SIZES.length - 1],
          Math.max(FONT_SIZES[0], size + (e.deltaY < 0 ? 1 : -1))
        )
        localStorage.setItem(FONT_SIZE_KEY, String(next))
        return next
      })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])
  const [loaded, setLoaded] = useState<{
    desc: LanguageDescription
    support: LanguageSupport
  } | null>(null)

  // 按文件名/后缀匹配语言，手动选择优先
  const autoDesc = useMemo(
    () =>
      LanguageDescription.matchFilename(
        languages,
        file.displayName.toLowerCase()
      ) ??
      languages.find((l) =>
        l.extensions.includes((file.suffix || '').toLowerCase())
      ) ??
      null,
    [file.displayName, file.suffix]
  )
  const [manualDesc, setManualDesc] = useState<LanguageDescription | null>(null)
  const activeDesc = manualDesc ?? autoDesc
  const langName = activeDesc?.name ?? 'plain'

  // 异步加载语言支持，加载完成前用旧值不生效
  useEffect(() => {
    if (!activeDesc) return
    let cancelled = false
    activeDesc.load().then((support) => {
      if (!cancelled) setLoaded({ desc: activeDesc, support })
    })
    return () => {
      cancelled = true
    }
  }, [activeDesc])
  const langSupport = loaded && loaded.desc === activeDesc ? loaded.support : null

  const handleSave = useCallback(() => {
    if (!dirty || saving) return
    onSave()
  }, [dirty, saving, onSave])

  // Ctrl+S 保存（对齐 ffs @keydown.s.ctrl）
  const saveKeymap = useMemo(
    () =>
      keymap.of([
        {
          key: 'Mod-s',
          preventDefault: true,
          run: () => {
            handleSave()
            return true
          },
        },
      ]),
    [handleSave]
  )

  // 系统配色主题：颜色全部引用主题 CSS 变量，明暗模式自动跟随
  const systemTheme = useMemo<Extension[]>(
    () => [
      EditorView.theme({
        '&': { color: 'var(--foreground)', backgroundColor: 'var(--background)' },
        '.cm-content': { caretColor: 'var(--primary)' },
        '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--primary)' },
        '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
          backgroundColor: 'var(--accent)',
        },
        '.cm-activeLine': { backgroundColor: 'var(--muted)' },
        '.cm-gutters': {
          backgroundColor: 'var(--background)',
          color: 'var(--muted-foreground)',
          border: 'none',
          borderRight: '1px solid var(--border)',
        },
        '.cm-activeLineGutter': { backgroundColor: 'var(--muted)', color: 'var(--foreground)' },
        '.cm-foldPlaceholder': {
          backgroundColor: 'var(--muted)',
          border: 'none',
          color: 'var(--muted-foreground)',
        },
      }),
      syntaxHighlighting(
        HighlightStyle.define([
          {
            tag: [tags.comment, tags.lineComment, tags.blockComment, tags.docComment],
            color: 'var(--editor-comment)',
            fontStyle: 'italic',
          },
          { tag: [tags.keyword, tags.modifier, tags.self, tags.null], color: 'var(--editor-keyword)' },
          {
            tag: [tags.string, tags.special(tags.string), tags.character, tags.regexp],
            color: 'var(--editor-string)',
          },
          {
            tag: [tags.number, tags.integer, tags.float, tags.bool, tags.constant(tags.variableName)],
            color: 'var(--editor-number)',
          },
          {
            tag: [tags.function(tags.variableName), tags.function(tags.propertyName), tags.macroName],
            color: 'var(--editor-function)',
          },
          { tag: [tags.typeName, tags.className, tags.namespace], color: 'var(--editor-type)' },
          { tag: tags.tagName, color: 'var(--editor-tag)' },
          { tag: tags.attributeName, color: 'var(--editor-attribute)' },
          { tag: tags.attributeValue, color: 'var(--editor-string)' },
          { tag: [tags.meta, tags.processingInstruction], color: 'var(--editor-comment)' },
          { tag: tags.heading, fontWeight: '600' },
          { tag: tags.strong, fontWeight: '600' },
          { tag: tags.emphasis, fontStyle: 'italic' },
          { tag: [tags.link, tags.url], color: 'var(--primary)', textDecoration: 'underline' },
          { tag: tags.invalid, color: 'var(--destructive)' },
        ])
      ),
    ],
    []
  )

  const themeExtension = useMemo(() => {
    if (themeName === 'system') return systemTheme
    const found = THEME_LIST.find((th) => th.name === themeName)
    return found ? [found.value] : []
  }, [themeName, systemTheme])

  return (
    <div ref={containerRef} className='flex min-h-0 flex-1 flex-col bg-background'>
      {/* 工具栏（对齐 ffs operate-wrapper：保存/自动换行/字号/语言/主题） */}
      <div className='flex flex-wrap items-center gap-x-4 gap-y-2 border-b px-4 py-2'>
        {!readOnly && (
          <Button
            variant='ghost'
            size='sm'
            className='h-7 gap-1 px-2'
            disabled={!dirty || saving}
            title={t('preview.save')}
            onClick={handleSave}
          >
            {saving ? (
              <Loader2 className='h-3.5 w-3.5 animate-spin' />
            ) : (
              <Save className='h-3.5 w-3.5' />
            )}
            {t('preview.save')}
          </Button>
        )}
        <div className='flex items-center gap-1.5'>
          <Checkbox
            id='cm-wrap'
            checked={wrap}
            onCheckedChange={(checked) => {
              const v = checked === true
              setWrap(v)
              localStorage.setItem(WRAP_KEY, String(v))
            }}
          />
          <Label htmlFor='cm-wrap' className='cursor-pointer text-sm'>
            {t('preview.wrap')}
          </Label>
        </div>
        <Select
          value={String(fontSize)}
          onValueChange={(v) => {
            setFontSize(Number(v))
            localStorage.setItem(FONT_SIZE_KEY, v)
          }}
        >
          <SelectTrigger size='sm' className='w-24'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {FONT_SIZES.map((size) => (
              <SelectItem key={size} value={String(size)}>
                {size} px
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={langName}
          onValueChange={(name) => {
            const desc = languages.find((l) => l.name === name)
            if (!desc) return
            setManualDesc(desc)
          }}
        >
          <SelectTrigger size='sm' className='w-44'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value='plain'>Plain Text</SelectItem>
            {languages
              .slice()
              .sort((a, b) => a.name.localeCompare(b.name))
              .map((l) => (
                <SelectItem key={l.name} value={l.name}>
                  {l.name}
                </SelectItem>
              ))}
          </SelectContent>
        </Select>
        <Select
          value={themeName}
          onValueChange={(v) => {
            setThemeName(v)
            localStorage.setItem(THEME_KEY, v)
          }}
        >
          <SelectTrigger size='sm' className='w-44'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value='system'>{t('preview.themeSystem')}</SelectItem>
            {THEME_LIST.map((th) => (
              <SelectItem key={th.name} value={th.name}>
                {th.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className='min-h-0 flex-1 overflow-hidden'>
        {loading ? (
          <div className='flex h-full items-center justify-center'>
            <Loader2 className='h-6 w-6 animate-spin text-muted-foreground' />
          </div>
        ) : (
          <Suspense
            fallback={
              <div className='flex h-full items-center justify-center'>
                <Loader2 className='h-6 w-6 animate-spin text-muted-foreground' />
              </div>
            }
          >
            <CodeMirror
              value={value}
              height='100%'
              className='h-full'
              theme={themeExtension.length ? 'none' : 'light'}
              editable={!readOnly}
              basicSetup={{ lineWrapping: wrap }}
              extensions={[
                saveKeymap,
                ...themeExtension,
                ...(langSupport ? [langSupport] : []),
              ]}
              style={{ fontSize: `${fontSize}px` }}
              onChange={onValueChange}
            />
          </Suspense>
        )}
      </div>
    </div>
  )
}
