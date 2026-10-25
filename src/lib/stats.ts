import type { ContentIndex } from '../content.ts'
import type { AnswersData, Exercise, OpenExercise, ProgressData, Section, Unit } from '../types.ts'
import { todayStr } from './date.ts'
import { isDue } from './srs.ts'

export interface Counts {
  total: number
  answered: number
  correct: number
}

export function countExercises(list: Exercise[], progress: ProgressData): Counts {
  let answered = 0
  let correct = 0
  for (const e of list) {
    const p = progress.exercises[e.id]
    if (p) {
      answered++
      if (p.last === 'correct') correct++
    }
  }
  return { total: list.length, answered, correct }
}

export function unitCounts(index: ContentIndex, unit: Unit, progress: ProgressData): Counts {
  return countExercises(index.exercisesByUnit.get(unit.id) ?? [], progress)
}

export interface SectionStats {
  section: Section
  units: Unit[]
  read: number
  exercises: Counts
}

export function sectionStats(index: ContentIndex, section: Section, progress: ProgressData): SectionStats {
  const units = section.unitIds.map((id) => index.unitById.get(id)).filter((u): u is Unit => !!u)
  const exs = units.flatMap((u) => index.exercisesByUnit.get(u.id) ?? [])
  return {
    section,
    units,
    read: units.filter((u) => progress.units[u.id]?.read).length,
    exercises: countExercises(exs, progress),
  }
}

export function dueExercises(index: ContentIndex, progress: ProgressData, today = todayStr()): Exercise[] {
  return index.content.exercises.filter((e) => isDue(progress.exercises[e.id], today))
}

export function wrongExercises(index: ContentIndex, progress: ProgressData): Exercise[] {
  return index.content.exercises.filter((e) => progress.exercises[e.id]?.last === 'wrong')
}

/** 尚未填寫的 factsToConfirm：回傳 { exercise, 事實 id[] } */
export function pendingFacts(index: ContentIndex, answers: AnswersData): Array<{ exercise: OpenExercise; missing: string[] }> {
  const out: Array<{ exercise: OpenExercise; missing: string[] }> = []
  for (const e of index.content.exercises) {
    if (e.type !== 'open' || e.factIds.length === 0) continue
    const facts = answers.answers[e.id]?.facts ?? {}
    const missing = e.factIds.filter((k) => !(facts[k] ?? '').trim())
    if (missing.length) out.push({ exercise: e, missing })
  }
  return out
}

export const stripTags = (html: string) =>
  html
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim()
