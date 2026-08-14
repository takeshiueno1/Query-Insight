import { Link } from 'react-router-dom'
import { CertificationSection, TalentProfileLoader } from '../components/TalentProfilePanel'
import { useAuth } from '../features/auth/auth-context'

export function CertificationsPage() {
  const { user } = useAuth()
  if (!user) return null

  return <>
    <div className="page-heading"><div><span className="eyebrow">タレント情報</span><h1>資格の確認・登録</h1><p>承認済みの資格を確認し、変更は申請として登録します</p></div><Link className="primary-button" to="/talent/new/CERTIFICATION">資格を登録</Link></div>
    <TalentProfileLoader employeePublicId={user.employeePublicId} errorMessage="資格を取得できませんでした。">{(profile) => <div className="talent-profile"><CertificationSection certifications={profile.certifications} /></div>}</TalentProfileLoader>
  </>
}
