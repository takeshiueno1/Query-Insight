import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'
import { api } from '../lib/api'
import { evaluationStatusLabels, type EvaluationRank, type ExecutiveDashboard } from '../types'

const ranks: EvaluationRank[] = ['S', 'A', 'B', 'C', 'D', 'F']

export function ExecutiveDashboardPage() {
  const { user } = useAuth()
  const query = useQuery({ queryKey: ['executive-evaluations', user?.accountPublicId], queryFn: () => api<ExecutiveDashboard>('/api/v1/executive/evaluations'), enabled: Boolean(user?.accountPublicId) })
  const [status, setStatus] = useState('ALL')
  const [deadline, setDeadline] = useState('ALL')
  const [period, setPeriod] = useState('ALL')
  const [department, setDepartment] = useState('ALL')
  const [grade, setGrade] = useState('ALL')

  if (query.isLoading) {
    return <div className="state-card" role="status" aria-label="全社評価を読込中" aria-busy="true">全社評価を読み込んでいます…</div>
  }
  if (query.isError) {
    return <div className="state-card" role="alert"><h1>全社評価を取得できませんでした</h1><button className="secondary-button" onClick={() => void query.refetch()}>再読み込み</button></div>
  }

  const data = query.data
  if (!data) return <div className="state-card" role="status" aria-label="全社評価の状態">評価対象はありません。</div>
  const departments = [...new Set(data.items.map((item) => item.departmentName ?? '未設定'))].sort()
  const periods = [...new Set(data.items.map((item) => item.periodName))].sort()
  const items = data.items.filter((item) => (status === 'ALL' || item.status === status)
    && (deadline === 'ALL' || (deadline === 'LATE') === item.late)
    && (period === 'ALL' || item.periodName === period)
    && (department === 'ALL' || (item.departmentName ?? '未設定') === department)
    && (grade === 'ALL' || item.finalGrade === grade))
  const baseEmpty = data.items.length === 0

  return <>
    <div className="page-heading"><div><h1>最終評価・経営ダッシュボード</h1><p>全社の評価状況と分布を確認し、個別に最終承認します。</p></div></div>
    <div className="metric-grid executive-metrics"><article className="metric-card"><span>全対象</span><strong>{data.counts.total}</strong></article><article className="metric-card"><span>最終承認待ち</span><strong>{data.counts.pending}</strong></article><article className="metric-card"><span>確定済み</span><strong>{data.counts.finalized}</strong></article><article className="metric-card"><span>期限超過</span><strong>{data.counts.overdue}</strong></article></div>
    <section className="card executive-filters" aria-label="評価一覧フィルター">
      <label>評価期<select value={period} onChange={(event) => setPeriod(event.target.value)}><option value="ALL">すべて</option>{periods.map((name) => <option key={name} value={name}>{name}</option>)}</select></label>
      <label>状態<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">すべて</option>{Object.entries(evaluationStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>期限<select value={deadline} onChange={(event) => setDeadline(event.target.value)}><option value="ALL">すべて</option><option value="LATE">期限超過</option><option value="ONTIME">期限内</option></select></label>
      <label>所属<select value={department} onChange={(event) => setDepartment(event.target.value)}><option value="ALL">すべて</option>{departments.map((name) => <option key={name} value={name}>{name}</option>)}</select></label>
      <label>総合ランク<select value={grade} onChange={(event) => setGrade(event.target.value)}><option value="ALL">すべて</option>{ranks.map((rank) => <option key={rank} value={rank}>{rank}</option>)}</select></label>
    </section>
    <section className="card table-card"><div className="card-header"><div><h2>全社評価一覧</h2><p>承認は一件ずつ内容を確認して行います。表示 {items.length}件</p></div></div><div className="table-scroll"><table><thead><tr><th>社員</th><th>所属</th><th>状態</th><th>総合ランク</th><th /></tr></thead><tbody>{items.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{item.departmentName ?? '未設定'}</td><td><span className={`status ${item.status === 'FINALIZED' ? 'success' : item.late ? 'danger' : 'warning'}`}>{evaluationStatusLabels[item.status] ?? '確認中'}</span></td><td>{item.finalGrade ?? '—'}</td><td><Link className="text-link" to={`/executive/evaluations/${item.publicId}`}>詳細 →</Link></td></tr>)}{items.length === 0 && <tr><td className="empty" colSpan={5}><p role="status" aria-label="全社評価の状態">{baseEmpty ? '評価対象はありません。' : '条件に一致する評価はありません。'}</p></td></tr>}</tbody></table></div></section>
    <section className="card distribution-card"><h2>部門別・ランク分布</h2>{data.distributions.length ? <div className="distribution-list">{data.distributions.map((row) => <div key={`${row.departmentName}-${row.grade}`}><span>{row.departmentName} / {row.grade}</span><strong>{row.employeeCount}名</strong></div>)}</div> : <p>確定済み評価が増えると、ここに分布を表示します。</p>}</section>
  </>
}
