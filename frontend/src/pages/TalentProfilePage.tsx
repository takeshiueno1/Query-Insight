import { TalentProfilePanel } from '../components/TalentProfilePanel'
import { useAuth } from '../features/auth/auth-context'

export function TalentProfilePage() {
  const { user } = useAuth()
  if (!user) return null
  return <>
    <div className="page-heading"><div><span className="eyebrow">SCR-006 / SCR-007 / SCR-008</span><h1>タレントプロフィール</h1><p>スキル、専門知識、業務経験、資格を一体で確認できます</p></div></div>
    <TalentProfilePanel employeePublicId={user.employeePublicId} />
  </>
}
