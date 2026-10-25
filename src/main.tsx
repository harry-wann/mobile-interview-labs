import { createRoot } from 'react-dom/client'
import { App } from './App.tsx'
import { initCloud } from './lib/cloud.ts'
import { captureLanToken } from './lib/lan.ts'
import { initSample } from './lib/grader.ts'
import { normalizeInitialHash } from './lib/router.ts'
import { initStore } from './lib/store.ts'
import { applyTheme } from './lib/theme.ts'
import './styles/tokens.css'
import './styles/base.css'
import './styles/layout.css'
import './styles/content.css'
import './styles/exercise.css'
import './styles/pages.css'

captureLanToken() // 區網：?t=<token> 存起來並從網址移除
applyTheme()
normalizeInitialHash()
void initStore()
// 靜態版：雲端存檔與 AI 評分都在渲染之後才非同步啟動，沒有 window.claude 時各自退回本機行為
void initCloud()
void initSample()
createRoot(document.getElementById('root')!).render(<App />)
