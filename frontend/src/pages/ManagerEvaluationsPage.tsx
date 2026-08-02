import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { ManagerEvaluationListItem } from '../types'

const statusLabel: Record<string, string> = {
  SELF_SUBMITTED: '上長評価待ち', MANAGER_IN_PROGRESS: '入力中', MANAGER_RETURNED: '社長差戻し',
  EXECUTIVE_REVIEW: '社長承認待ち', FINALIZED: '確定', SELF_RETURNED: '本人差戻し',
}

export function ManagerEvaluationsPage() {
  const query = useQuery({ queryKey: ['manager-evaluations'], queryFn: () => api<ManagerEvaluationListItem[]>('/api/v1/manager-evaluations') })
  const [status, setStatus] = useState('ALL')
  const [deadline, setDeadline] = useState('ALL')
  if (query.isLoading) return <div className="state-card">担当評価を読み込んでいます…</div>
  const items = query.data?.filter((item) => (status === 'ALL' || item.status === status)
    && (deadline === 'ALL' || (deadline === 'LATE') === item.late)) ?? []
  return <><div className="page-heading"><div><span className="eyebrow">SCR-010</span><h1>上長評価</h1><p>担当社員の自己評価を確認し、社長へ提出します。</p></div></div>
    <section className="card executive-filters" aria-label="担当評価フィルター"><label>状態<select value={status} onChange={(e) => setStatus(e.target.value)}><option value="ALL">すべて</option>{Object.entries(statusLabel).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>期限<select value={deadline} onChange={(e) => setDeadline(e.target.value)}><option value="ALL">すべて</option><option value="LATE">期限超過</option><option value="ONTIME">期限内</option></select></label></section>
    <section className="card table-card"><div className="card-header"><div><h2>担当者一覧</h2><p>表示 {items.length}件</p></div></div><div className="table-scroll"><table><thead><tr><th>社員</th><th>所属</th><th>評価期間</th><th>状態</th><th>期限</th><th /></tr></thead><tbody>
      {items.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{item.departmentName ?? '未設定'}</td><td>{item.periodName}</td><td><span className={`status ${item.status === 'FINALIZED' ? 'success' : 'warning'}`}>{statusLabel[item.status] ?? item.status}</span></td><td>{item.late ? <span className="status danger">期限超過</span> : '期限内'}</td><td><Link className="text-link" to={`/evaluations/manager/${item.publicId}`}>確認する →</Link></td></tr>)}
      {!items.length && <tr><td className="empty" colSpan={6}>条件に一致する評価はありません。</td></tr>}
    </tbody></table></div></section></>
}
