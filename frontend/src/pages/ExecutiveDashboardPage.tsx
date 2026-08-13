import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { ExecutiveDashboard } from '../types'

export function ExecutiveDashboardPage() {
  const query = useQuery({ queryKey: ['executive-evaluations'], queryFn: () => api<ExecutiveDashboard>('/api/v1/executive/evaluations') })
  const [status, setStatus] = useState('ALL')
  const [deadline, setDeadline] = useState('ALL')
  const [period, setPeriod] = useState('ALL')
  const [department, setDepartment] = useState('ALL')
  const [grade, setGrade] = useState('ALL')
  if (query.isLoading) return <div className="state-card">経営判断データを読み込んでいます…</div>
  const data = query.data
  const departments = [...new Set(data?.items.map((item) => item.departmentName ?? '未設定') ?? [])].sort()
  const periods = [...new Set(data?.items.map((item) => item.periodName) ?? [])].sort()
  const items = data?.items.filter((item) => (status === 'ALL' || item.status === status)
    && (deadline === 'ALL' || (deadline === 'LATE') === item.late)
    && (period === 'ALL' || item.periodName === period)
    && (department === 'ALL' || (item.departmentName ?? '未設定') === department)
    && (grade === 'ALL' || (item.finalGrade ?? '未確定') === grade)) ?? []
  return <><div className="page-heading"><div><span className="eyebrow">FINAL REVIEW</span><h1>最終評価・経営ダッシュボード</h1><p>全社の評価状況と分布を確認し、個別に最終承認します。</p></div></div>
    <div className="metric-grid executive-metrics"><article className="metric-card"><span>全対象</span><strong>{data?.counts.total ?? 0}</strong></article><article className="metric-card"><span>最終承認待ち</span><strong>{data?.counts.pending ?? 0}</strong></article><article className="metric-card"><span>確定済み</span><strong>{data?.counts.finalized ?? 0}</strong></article><article className="metric-card"><span>期限超過</span><strong>{data?.counts.overdue ?? 0}</strong></article></div>
    <section className="card executive-filters" aria-label="評価一覧フィルター"><label>評価期<select value={period} onChange={(e) => setPeriod(e.target.value)}><option value="ALL">すべて</option>{periods.map((name) => <option key={name} value={name}>{name}</option>)}</select></label><label>状態<select value={status} onChange={(e) => setStatus(e.target.value)}><option value="ALL">すべて</option><option value="EXECUTIVE_REVIEW">最終承認待ち</option><option value="MANAGER_RETURNED">上長差戻し</option><option value="FINALIZED">確定済み</option><option value="SELF_SUBMITTED">上長評価待ち</option></select></label><label>期限<select value={deadline} onChange={(e) => setDeadline(e.target.value)}><option value="ALL">すべて</option><option value="LATE">期限超過</option><option value="ONTIME">期限内</option></select></label><label>所属<select value={department} onChange={(e) => setDepartment(e.target.value)}><option value="ALL">すべて</option>{departments.map((name) => <option key={name} value={name}>{name}</option>)}</select></label><label>等級<select value={grade} onChange={(e) => setGrade(e.target.value)}><option value="ALL">すべて</option><option value="未確定">未確定</option>{['S','A','B','C'].map((value) => <option key={value} value={value}>{value}</option>)}</select></label></section>
    <section className="card table-card"><div className="card-header"><div><h2>全社評価一覧</h2><p>承認は一件ずつ内容を確認して行います。表示 {items.length}件</p></div></div><div className="table-scroll"><table><thead><tr><th>社員</th><th>所属</th><th>状態</th><th>確定点</th><th>等級</th><th /></tr></thead><tbody>{items.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{item.departmentName ?? '未設定'}</td><td><span className={`status ${item.status === 'FINALIZED' ? 'success' : item.late ? 'danger' : 'warning'}`}>{item.status}</span></td><td>{item.finalScore ?? '—'}</td><td>{item.finalGrade ?? '—'}</td><td><Link className="text-link" to={`/executive/evaluations/${item.publicId}`}>詳細 →</Link></td></tr>)}{items.length === 0 && <tr><td className="empty" colSpan={6}>条件に一致する評価はありません。</td></tr>}</tbody></table></div></section>
    <section className="card distribution-card"><h2>部門別・等級分布</h2>{data?.distributions.length ? <div className="distribution-list">{data.distributions.map((row) => <div key={`${row.departmentName}-${row.grade}`}><span>{row.departmentName} / {row.grade}</span><strong>{row.employeeCount}名</strong></div>)}</div> : <p>確定済み評価が増えると、ここに分布を表示します。</p>}</section></>
}
