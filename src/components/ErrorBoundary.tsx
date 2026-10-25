import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  /** 簡短說明這個區塊是什麼，例如「這題」 */
  what?: string
  /** 內容更新或使用者按「重試」時，用新的 key 重新掛載（由呼叫端放在 key 上） */
  onReset?: () => void
}
interface S {
  error: Error | null
}

/** 一個區塊出錯不會讓整頁白屏：顯示錯誤與「重試」 */
export class ErrorBoundary extends Component<Props, S> {
  state: S = { error: null }

  static getDerivedStateFromError(error: Error): S {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="boundary" role="alert">
        <strong>{this.props.what ?? '這個區塊'}顯示時發生錯誤</strong>
        <p className="muted small">可能是內容剛更新。按「重試」重新載入這一塊；如果一直出現，請重新整理頁面。</p>
        <pre className="run-error">{this.state.error.message}</pre>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => {
            this.props.onReset?.()
            this.setState({ error: null })
          }}
        >
          重試
        </button>
      </div>
    )
  }
}
