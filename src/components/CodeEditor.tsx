import { indentWithTab } from '@codemirror/commands'
import { HighlightStyle, StreamLanguage, syntaxHighlighting } from '@codemirror/language'
import { kotlin } from '@codemirror/legacy-modes/mode/clike'
import { swift } from '@codemirror/legacy-modes/mode/swift'
import { EditorView, keymap } from '@codemirror/view'
import { tags as t } from '@lezer/highlight'
import CodeMirror from '@uiw/react-codemirror'
import { useMemo } from 'react'
import type { RunLang } from '../types.ts'

// 顏色全部走 CSS 變數（--syn-*），所以跟著網站的 light／dark 主題切換
const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.controlKeyword, t.definitionKeyword, t.operatorKeyword], color: 'var(--syn-keyword)' },
  { tag: [t.string, t.special(t.string), t.regexp], color: 'var(--syn-string)' },
  { tag: [t.number, t.bool, t.atom, t.null, t.integer, t.float], color: 'var(--syn-number)' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: 'var(--syn-comment)', fontStyle: 'italic' },
  { tag: [t.typeName, t.className, t.namespace], color: 'var(--syn-type)' },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.definition(t.variableName)], color: 'var(--syn-function)' },
  { tag: [t.meta, t.annotation, t.attributeName], color: 'var(--syn-attr)' },
])

const theme = EditorView.theme({
  '&': { color: 'var(--text)', backgroundColor: 'var(--code-bg)', fontSize: '13.5px' },
  '&.cm-focused': { outline: '2px solid var(--focus)', outlineOffset: '-1px' },
  '.cm-scroller': { fontFamily: 'var(--font-mono)', lineHeight: '1.6', maxHeight: '460px' },
  '.cm-content': { caretColor: 'var(--primary)', padding: '8px 0' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--primary)' },
  '.cm-gutters': { backgroundColor: 'var(--code-bg)', color: 'var(--text-3)', border: 'none' },
  '.cm-activeLine': { backgroundColor: 'var(--surface-2)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--surface-2)', color: 'var(--text-2)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': { backgroundColor: 'var(--primary-soft)' },
  '.cm-matchingBracket': { backgroundColor: 'var(--primary-soft)', outline: '1px solid var(--border-strong)' },
})

const base = [
  theme,
  syntaxHighlighting(highlight),
  keymap.of([indentWithTab]),
  EditorView.contentAttributes.of({ autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false', 'aria-label': '程式碼編輯器' }),
]

export function CodeEditor({ lang, value, onChange, id }: { lang: RunLang; value: string; onChange: (v: string) => void; id: string }) {
  const extensions = useMemo(() => [...base, StreamLanguage.define(lang === 'swift' ? swift : kotlin)], [lang])
  return (
    <div className="editor" id={id}>
      <CodeMirror
        value={value}
        onChange={onChange}
        theme="none"
        extensions={extensions}
        minHeight="140px"
        basicSetup={{ lineNumbers: true, foldGutter: false, highlightActiveLine: true, autocompletion: false, searchKeymap: false, dropCursor: false }}
      />
    </div>
  )
}
