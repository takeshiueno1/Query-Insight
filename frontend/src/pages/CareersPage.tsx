import { Link } from 'react-router-dom'
import { CareerSection, TalentProfileLoader } from '../components/TalentProfilePanel'
import { TalentSubmissionStatusPanel } from '../components/TalentSubmissionStatusPanel'
import { useAuth } from '../features/auth/auth-context'

export function CareersPage() {
  const { user } = useAuth()
  if (!user) return null

  return <>
    <div className="page-heading"><div><span className="eyebrow">タレント情報</span><h1>業務経歴の確認・登録</h1><p>承認済みの業務経歴を確認し、変更は申請として登録します</p></div><Link className="primary-button" to="/talent/new/CAREER">業務経歴を登録</Link></div>
    <TalentProfileLoader employeePublicId={user.employeePublicId} errorMessage="業務経歴を取得できませんでした。">{(profile) => <div className="talent-profile"><CareerSection careers={profile.careers} /></div>}</TalentProfileLoader>
    <TalentSubmissionStatusPanel types={['CAREER']} />
  </>
}
