import { useMemo } from 'react'

/**
 * 将文本中命中的关键词片段用 <mark> 高亮
 */
export function Highlight({
  text,
  keyword,
}: {
  text: string
  keyword?: string
}) {
  const parts = useMemo(() => {
    const kw = keyword?.trim()
    if (!kw) return [text]
    const chunks: string[] = []
    const flag = kw && /[^\u4e00-\u9fa5A-Za-z0-9_]/g.test(kw) ? 'i' : 'iu'
    try {
      const re = new RegExp(`(${kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, flag)
      const split = text.split(re)
      for (let i = 0; i < split.length; i++) {
        if (split[i] === '') continue
        if (re.test(split[i])) {
          chunks.push(`<mark>${split[i]}</mark>`)
        } else {
          chunks.push(split[i])
        }
      }
    } catch {
      return [text]
    }
    return chunks
  }, [text, keyword])

  return (
    <>
      {parts.map((part, i) =>
        part.startsWith('<mark>') ? (
          <mark key={i} className='rounded-sm bg-accent px-0.5 text-inherit'>
            {part.slice(6, -7)}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        )
      )}
    </>
  )
}