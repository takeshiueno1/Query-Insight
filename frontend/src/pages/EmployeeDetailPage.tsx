import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api } from '../lib/api'
import type { EmployeeDetail } from '../types'
import { TalentProfilePanel } from '../components/TalentProfilePanel'

export function EmployeeDetailPage() {
  const { publicId } = useParams()
  const query = useQuery({ queryKey: ['employee', publicId], enabled: Boolean(publicId), queryFn: () => api<EmployeeDetail>(`/api/v1/employees/${publicId}`) })
  if (query.isLoading) return <div className="state-card">社員情報を読み込んでいます…</div>
  if (!query.data) return <div className="state-card"><h1>社員情報を表示できません</h1><Link to="/employees">社員検索へ戻る</Link></div>
  const employee = query.data
  return <><div className="page-heading"><div><span className="eyebrow">社員情報</span><h1>社員詳細</h1><p>{employee.employeeNo}</p></div><Link className="secondary-button" to="/employees">一覧へ戻る</Link></div>
    <section className="profile-hero"><div className="avatar">{employee.lastName[0]}{employee.firstName[0]}</div><div><h2>{employee.lastName} {employee.firstName}</h2><p>{employee.departmentName ?? '所属未設定'} / {employee.positionName ?? '役職未設定'}</p></div><span className="status success">{employee.employmentStatus}</span></section>
    <div className="dashboard-grid"><section className="card"><h2>基本情報</h2><dl className="detail-list"><div><dt>メール</dt><dd>{employee.email}</dd></div><div><dt>入社日</dt><dd>{employee.hireDate ?? '未設定'}</dd></div><div><dt>上長</dt><dd>{employee.managerName ?? '未設定'}</dd></div><div><dt>更新日時</dt><dd>{new Date(employee.updatedAt).toLocaleString('ja-JP')}</dd></div></dl></section>
      <section className="card"><h2>関連情報</h2><div className="shortcut-grid"><Link to="/skills/edit">本人プロフィール</Link><Link to="/evaluations/history">評価履歴</Link></div></section></div>
    <TalentProfilePanel employeePublicId={employee.publicId} /></>
}
