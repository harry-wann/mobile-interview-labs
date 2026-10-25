// Web Speech API（語音輸入）。不支援的瀏覽器回傳 supported=false，UI 就隱藏按鈕。
import { useCallback, useEffect, useRef, useState } from 'react'

interface RecognitionLike {
  abort?(): void
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((e: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null
  onerror: ((e: { error?: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
}

function getCtor(): (new () => RecognitionLike) | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as Record<string, unknown>
  return ((w.SpeechRecognition ?? w.webkitSpeechRecognition) as (new () => RecognitionLike) | undefined) ?? null
}

export const speechSupported = () => getCtor() !== null

export function useSpeech(lang: 'zh' | 'ja', onFinalText: (text: string) => void) {
  const supported = speechSupported()
  const [listening, setListening] = useState(false)
  const [interim, setInterim] = useState('')
  const [error, setError] = useState('')
  const recRef = useRef<RecognitionLike | null>(null)
  const cbRef = useRef(onFinalText)
  cbRef.current = onFinalText

  const stop = useCallback(() => {
    recRef.current?.stop()
  }, [])

  /** 立刻放棄辨識並解除 handler：之後不會再有任何文字寫進（可能已經換題的）回答 */
  const discard = useCallback(() => {
    const rec = recRef.current
    if (!rec) return
    rec.onresult = null
    rec.onerror = null
    rec.onend = null
    try {
      if (rec.abort) rec.abort()
      else rec.stop()
    } catch {
      /* 已經停了 */
    }
    recRef.current = null
    setListening(false)
    setInterim('')
  }, [])

  const start = useCallback(() => {
    const Ctor = getCtor()
    if (!Ctor) return
    setError('')
    const rec = new Ctor()
    rec.lang = lang === 'ja' ? 'ja-JP' : 'zh-TW'
    rec.continuous = true
    rec.interimResults = true
    rec.onresult = (e) => {
      let finalText = ''
      let interimText = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i]
        if (r.isFinal) finalText += r[0].transcript
        else interimText += r[0].transcript
      }
      setInterim(interimText)
      if (finalText) cbRef.current(finalText)
    }
    rec.onerror = (e) => {
      setError(e.error === 'not-allowed' ? '沒有麥克風權限' : e.error === 'no-speech' ? '沒有偵測到聲音' : `語音輸入失敗（${e.error ?? '未知'}）`)
    }
    rec.onend = () => {
      setListening(false)
      setInterim('')
      recRef.current = null
    }
    recRef.current = rec
    try {
      rec.start()
      setListening(true)
    } catch {
      setError('無法啟動語音輸入')
    }
  }, [lang])

  // 卸載（換頁、換題）或切換語言時放棄目前的辨識
  useEffect(() => discard, [discard])
  const langRef = useRef(lang)
  useEffect(() => {
    if (langRef.current !== lang) {
      langRef.current = lang
      discard()
    }
  }, [lang, discard])

  return { supported, listening, interim, error, start, stop }
}
