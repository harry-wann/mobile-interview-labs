// 離開頁面時（pagehide／分頁被隱藏）的 flush，依階段執行（同一階段內依註冊順序）：
//   early：各元件把暫存內容（還在 debounce 的輸入）丟進 store
//   final：store 寫 localStorage、把 delta 送到本機 server
//   sync ：遠端寫入（雲端存檔）；一定在所有元件的 early 之後，所以最後一次編輯也會一起寫上去
const phases = { early: new Set<() => void>(), final: new Set<() => void>(), sync: new Set<() => void>() }
export type PageHidePhase = keyof typeof phases
let installed = false

export function runPageHide() {
  for (const set of [phases.early, phases.final, phases.sync]) {
    for (const f of set) {
      try {
        f()
      } catch {
        /* 一個 flush 失敗不影響其他 */
      }
    }
  }
}

function install() {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.addEventListener('pagehide', runPageHide)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') runPageHide()
  })
}

export function onPageHide(fn: () => void, phase: PageHidePhase = 'early'): () => void {
  install()
  const set = phases[phase]
  set.add(fn)
  return () => {
    set.delete(fn)
  }
}
