import { TalentProfilePanel } from '../components/TalentProfilePanel'
import { useAuth } from '../features/auth/auth-context'
import { Link } from 'react-router-dom'

export function TalentProfilePage() {
  const { user } = useAuth()
  if (!user) return null
  return <>
    <div className="page-heading"><div><span className="eyebrow">SCR-006 / SCR-007 / SCR-008</span><h1>タレントプロフィール</h1><p>承認済みのスキル、専門知識、業務経験、資格を確認できます</p></div><div className="form-actions"><Link className="secondary-button" to="/master-requests">マスタ追加申請</Link><Link className="primary-button" to="/talent/new/SKILL">新規申請</Link></div></div>
    <TalentProfilePanel employeePublicId={user.employeePublicId} />
  </>
}
