import initial from 'virtual:prep-content'
import { useSyncExternalStore } from 'react'
import type { Content, ContentIssue, Exercise, Section, Unit } from './types.ts'

export interface ContentIndex {
  content: Content
  sections: Section[]
  sectionById: Map<string, Section>
  unitById: Map<string, Unit>
  exerciseById: Map<string, Exercise>
  /** 依 section 順序、單元順序攤平 */
  unitList: Unit[]
  exercisesByUnit: Map<string, Exercise[]>
  prevUnit: Map<string, Unit | undefined>
  nextUnit: Map<string, Unit | undefined>
  errors: ContentIssue[]
}

function buildIndex(content: Content): ContentIndex {
  const sectionById = new Map(content.sections.map((s) => [s.id, s]))
  const unitById = new Map(content.units.map((u) => [u.id, u]))
  const exerciseById = new Map(content.exercises.map((e) => [e.id, e]))
  const unitList: Unit[] = []
  for (const s of content.sections) for (const id of s.unitIds) {
    const u = unitById.get(id)
    if (u) unitList.push(u)
  }
  const exercisesByUnit = new Map<string, Exercise[]>()
  for (const u of unitList) exercisesByUnit.set(u.id, u.exerciseIds.map((id) => exerciseById.get(id)).filter((e): e is Exercise => !!e))
  const prevUnit = new Map<string, Unit | undefined>()
  const nextUnit = new Map<string, Unit | undefined>()
  unitList.forEach((u, i) => {
    prevUnit.set(u.id, unitList[i - 1])
    nextUnit.set(u.id, unitList[i + 1])
  })
  return {
    content,
    sections: content.sections,
    sectionById,
    unitById,
    exerciseById,
    unitList,
    exercisesByUnit,
    prevUnit,
    nextUnit,
    errors: content.issues.filter((i) => i.level === 'error'),
  }
}

/**
 * 題目內容的指紋：內容熱更新後題目有變，key 就會變 → 該題卡片重新掛載（舊的作答 state 不會和新題目對不上）。
 * 以物件為 key 快取，只有內容真的換了才重算。
 */
const keyCache = new WeakMap<object, string>()
export function exerciseKey(ex: Exercise): string {
  let k = keyCache.get(ex)
  if (!k) {
    const text = JSON.stringify(ex)
    let h = 2166136261
    for (let i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i)
      h = Math.imul(h, 16777619)
    }
    k = `${ex.id}:${(h >>> 0).toString(36)}`
    keyCache.set(ex, k)
  }
  return k
}

let index = buildIndex(initial)
const listeners = new Set<() => void>()

export function setContent(c: Content) {
  index = buildIndex(c)
  listeners.forEach((l) => l())
}

export function getIndex() {
  return index
}

/** 內容換了（dev 熱更新）時通知 */
export function subscribeContent(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function useContent(): ContentIndex {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => index,
  )
}

// dev：content/ 有變動時，server 會推事件；重新抓內容並就地更新（不整頁重載，不會丟掉正在作答的內容）
if (import.meta.hot) {
  import.meta.hot.on('prep:content', async () => {
    try {
      const r = await fetch('/api/content', { cache: 'no-store' })
      if (r.ok) setContent((await r.json()) as Content)
    } catch {
      /* 下次變動再試 */
    }
  })
}
