import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { RadarChart } from '../components/RadarChart'
import { api } from '../lib/api'
import type { Dashboard } from '../types'

export function DashboardPage() {
  const query = useQuery({ queryKey: ['dashboard'], queryFn: () => api<Dashboard>('/api/v1/dashboard/me') })
  if (query.isLoading) return <PageState title="ダッシュボードを読み込んでいます" />
  if (query.isError || !query.data) return <PageState title="ダッシュボードを表示できません" detail="時間をおいて再読み込みしてください。" />
  const { profile, scores, unreadNotifications } = query.data
  const evaluated = scores.filter((score) => score.level > 0).length
  return (
    <>
      <div className="page-heading"><div><span className="eyebrow">SCR-002</span><h1>個人ダッシュボード</h1><p>{profile.name}さんの最新状況</p></div><span className="updated">更新 {new Date(profile.updatedAt).toLocaleDateString('ja-JP')}</span></div>
      <section className="metric-grid">
        <article className="metric-card"><span>プロフィール</span><strong>{profile.employeeNo}</strong><small>{profile.department ?? '所属未設定'}</small></article>
        <article className="metric-card"><span>評価入力</span><strong>{evaluated}/6軸</strong><small>{evaluated === 6 ? '入力済み' : '入力を確認してください'}</small></article>
        <article className="metric-card"><span>未読通知</span><strong>{unreadNotifications}件</strong><Link to="/notifications">通知を確認</Link></article>
      </section>
      <section className="dashboard-grid">
        <article className="card"><div className="card-header"><div><h2>能力バランス</h2><p>現在の自己評価。未評価は0点として扱いません。</p></div><Link className="text-link" to="/evaluations/self">評価を編集</Link></div><RadarChart scores={scores} /></article>
        <article className="card"><div className="card-header"><div><h2>次にやること</h2><p>期限と状態に基づく案内</p></div></div>
          <ul className="task-list">
            <li><span className="task-icon">1</span><div><strong>自己評価の根拠を入力</strong><small>6軸すべてに具体的な実績を記録します</small></div><Link to="/evaluations/self">開く</Link></li>
            <li><span className="task-icon muted">2</span><div><strong>プロフィールを確認</strong><small>所属・役職・連絡先の最新性を確認します</small></div><Link to="/employees">確認</Link></li>
            <li><span className="task-icon muted">3</span><div><strong>通知を確認</strong><small>差し戻しや期限の連絡を見落とさないようにします</small></div><Link to="/notifications">確認</Link></li>
          </ul>
        </article>
      </section>
    </>
  )
}
function PageState({ title, detail }: { title: string; detail?: string }) {
  return <div className="state-card"><div className="spinner" /><h1>{title}</h1>{detail && <p>{detail}</p>}</div>
}
