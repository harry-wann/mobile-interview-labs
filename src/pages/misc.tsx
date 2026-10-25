import { Link } from '../components/ui.tsx'

export function NotFound({ what }: { what: string }) {
  return (
    <div className="page">
      <h1>找不到{what}</h1>
      <p className="lead">可能是網址打錯，或這個單元因內容錯誤被略過（看頁面頂部的「內容錯誤」清單）。</p>
      <p>
        <Link to="home" className="btn btn-primary">
          回首頁
        </Link>
      </p>
    </div>
  )
}
