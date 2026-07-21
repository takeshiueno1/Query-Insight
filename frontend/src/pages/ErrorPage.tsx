import { Link } from 'react-router-dom'
export function ErrorPage({ forbidden = false }: { forbidden?: boolean }) {
  return <div className="error-page"><div className="error-code">{forbidden ? '403' : '!'}</div><h1>{forbidden ? 'この画面を表示する権限がありません' : '処理を完了できませんでした'}</h1><p>{forbidden ? '必要な場合はシステム管理者へ権限をご確認ください。' : '時間をおいて再度お試しください。'}</p><Link className="primary-button" to="/">ダッシュボードへ戻る</Link></div>
}
