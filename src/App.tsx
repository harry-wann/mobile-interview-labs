import { useEffect, useMemo, useState } from 'react'
import { useContent } from './content.ts'
import { ErrorBoundary } from './components/ErrorBoundary.tsx'
import { Sidebar } from './components/Sidebar.tsx'
import { IssuesPanel, SyncNotice, Topbar } from './components/Topbar.tsx'
import { parseToken, peekNavState, useRouteToken } from './lib/router.ts'
import { Answers } from './pages/Answers.tsx'
import { Home } from './pages/Home.tsx'
import { Mock } from './pages/Mock.tsx'
import { Plan } from './pages/Plan.tsx'
import { Practice } from './pages/Practice.tsx'
import { SectionPage } from './pages/SectionPage.tsx'
import { UnitPage } from './pages/UnitPage.tsx'
import { Wrong } from './pages/Wrong.tsx'

export function App() {
  const token = useRouteToken()
  const route = useMemo(() => parseToken(token), [token])
  const { errors } = useContent()
  const [menuOpen, setMenuOpen] = useState(false)
  const [issuesOpen, setIssuesOpen] = useState(false)

  // 換頁回到頂端（除非是要聚焦某一題）
  useEffect(() => {
    if (peekNavState()?.focus) return
    window.scrollTo({ top: 0, left: 0 })
  }, [token])

  useEffect(() => {
    if (errors.length === 0) setIssuesOpen(false)
  }, [errors.length])

  return (
    <div className="app">
      <div className="app-inner">
      <Topbar menuOpen={menuOpen} onMenu={() => setMenuOpen((o) => !o)} errorCount={errors.length} issuesOpen={issuesOpen} onToggleIssues={() => setIssuesOpen((o) => !o)} />
      <SyncNotice />
      {issuesOpen && errors.length > 0 && <IssuesPanel onClose={() => setIssuesOpen(false)} />}
      <div className="layout">
        <Sidebar route={route} open={menuOpen} onClose={() => setMenuOpen(false)} />
        <main className="main" id="main">
          <ErrorBoundary key={token} what="這個頁面">
          {route.name === 'home' && <Home />}
          {route.name === 'plan' && <Plan />}
          {route.name === 'practice' && <Practice />}
          {route.name === 'wrong' && <Wrong />}
          {route.name === 'mock' && <Mock />}
          {route.name === 'answers' && <Answers />}
          {route.name === 'section' && <SectionPage sectionId={route.id} />}
          {route.name === 'unit' && <UnitPage key={route.id} unitId={route.id} />}
          </ErrorBoundary>
        </main>
      </div>
      </div>
    </div>
  )
}
