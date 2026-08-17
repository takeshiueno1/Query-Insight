import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'
import { api } from '../lib/api'
import { evaluationStatusLabels, type ManagerEvaluationListItem } from '../types'

export function ManagerEvaluationsPage() {
  const { user } = useAuth()
  const query = useQuery({ queryKey: ['manager-evaluations', user?.accountPublicId], queryFn: () => api<ManagerEvaluationListItem[]>('/api/v1/manager-evaluations'), enabled: Boolean(user?.accountPublicId) })
  const [status, setStatus] = useState('ALL')
  const [deadline, setDeadline] = useState('ALL')
  if (query.isLoading) return <div className="state-card" role="status" aria-label="担当評価を読込中" aria-busy="true">担当評価を読み込んでいます…</div>
  if (query.isError) return <div className="state-card" role="alert"><h1>担当評価を取得できませんでした</h1><p>時間をおいて再度お試しください。</p><button className="secondary-button" onClick={() => void query.refetch()}>再読み込み</button></div>
  const items = query.data?.filter((item) => (status === 'ALL' || item.status === status)
    && (deadline === 'ALL' || (deadline === 'LATE') === item.late)) ?? []
  const hasAnyItems = Boolean(query.data?.length)
  return <><div className="page-heading"><div><h1>上長評価入力</h1><p>直属部下の評価を入力し、最終承認者へ提出します。</p></div></div>
    <section className="card executive-filters" aria-label="担当評価フィルター"><label>状態<select value={status} onChange={(e) => setStatus(e.target.value)}><option value="ALL">すべて</option>{Object.entries(evaluationStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>期限<select value={deadline} onChange={(e) => setDeadline(e.target.value)}><option value="ALL">すべて</option><option value="LATE">期限超過</option><option value="ONTIME">期限内</option></select></label></section>
    <section className="card table-card"><div className="card-header"><div><h2>担当者一覧</h2><p>表示 {items.length}件</p></div></div><div className="table-scroll"><table><thead><tr><th>社員</th><th>所属</th><th>評価期間</th><th>状態</th><th>期限</th><th /></tr></thead><tbody>
      {items.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{item.departmentName ?? '未設定'}</td><td>{item.periodName}</td><td><span className={`status ${item.status === 'FINALIZED' ? 'success' : 'warning'}`}>{evaluationStatusLabels[item.status] ?? '確認中'}</span></td><td>{item.late ? <span className="status danger">期限超過</span> : '期限内'}</td><td><Link className="text-link" to={`/evaluations/manager/${item.publicId}`}>確認する →</Link></td></tr>)}
      {!items.length && <tr><td className="empty" colSpan={6}><span role="status" aria-label="担当評価の状態">{hasAnyItems ? '条件に一致する評価はありません。' : '担当する評価はありません。'}</span></td></tr>}
    </tbody></table></div></section></>
}
