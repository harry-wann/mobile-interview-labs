// 輸入框（回答、待確認事實）與 store 之間的同步規則。和 React 無關：AnswerField 的 hook 與回歸測試共用同一份邏輯。
//  - 輸入框記住「開始編輯時所依據的版本」（base：值＋時間戳記）。
//  - store 的內容變了：沒在編輯（沒有本地修改、也沒有 focus）就直接換；編輯中只記下「有遠端新版本」（顯示提示），不換掉輸入框。
//  - 儲存時把 base 交給 store：如果 store 現在的內容和 base 不同（編輯中別台改了、使用者沒看過），
//    那個版本會先進 history，patch 也帶 archive 讓 server 封存它現有的版本（store.saveField）。兩個版本都不會遺失。
import type { AnswerField } from './data.ts'
import { saveField, type FactMeta, type FieldBase } from './store.ts'

export class FieldEditor {
  /** 輸入框目前的內容 */
  value: string
  /** 輸入框的內容是從 store 的哪個版本開始編輯的 */
  base: FieldBase
  focused = false
  /** 編輯中 store 有了使用者還沒看過的版本 */
  pendingRemote = false

  constructor(
    readonly exerciseId: string,
    readonly field: AnswerField,
    stored: FieldBase,
    /** 事實欄位：題目文字與在目前內容中的位置（存進 factQ／factPos：內容移除或改字之後顯示、自動搬移用） */
    readonly meta?: FactMeta,
  ) {
    this.value = stored.v
    this.base = { ...stored }
  }

  /** 有沒有還沒存進 store、或還沒被 store 確認的本地修改 */
  get edited() {
    return this.value !== this.base.v
  }

  /** store 的內容變了。回傳 true 表示輸入框的內容被換成 store 的版本 */
  sync(stored: FieldBase): boolean {
    if (stored.v === this.base.v) {
      // 同一份內容（例如自己的儲存被 server 確認、換成 server 的時間戳記）
      this.base = { ...stored }
      return false
    }
    if (stored.v === this.value) {
      // 自己的儲存落地了
      this.base = { ...stored }
      this.pendingRemote = false
      return false
    }
    if (!this.edited && !this.focused) {
      this.value = stored.v
      this.base = { ...stored }
      this.pendingRemote = false
      return true
    }
    this.pendingRemote = true // 編輯中：不換掉，只提示
    return false
  }

  input(v: string) {
    this.value = v
  }

  focus() {
    this.focused = true
  }

  /** 失去焦點：沒有本地修改、又有遠端新版本 → 換成 store 的版本。回傳 true 表示換了 */
  blur(stored: FieldBase): boolean {
    this.focused = false
    if (this.pendingRemote && !this.edited) {
      this.value = stored.v
      this.base = { ...stored }
      this.pendingRemote = false
      return true
    }
    return false
  }

  /** 把輸入框的內容存進 store（debounce 到期、卸載、離開頁面、AI 評分前） */
  save() {
    const r = saveField(this.exerciseId, this.field, this.value, this.base, this.meta)
    if (r) this.base = r
  }
}
