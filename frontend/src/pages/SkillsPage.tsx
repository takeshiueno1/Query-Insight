import { Link } from 'react-router-dom'
import { KnowledgeSection, SkillSection, TalentProfileLoader } from '../components/TalentProfilePanel'
import { TalentSubmissionStatusPanel } from '../components/TalentSubmissionStatusPanel'
import { useAuth } from '../features/auth/auth-context'

export function SkillsPage() {
  const { user } = useAuth()
  if (!user) return null

  return <>
    <div className="page-heading"><div><span className="eyebrow">タレント情報</span><h1>スキル情報の確認・登録</h1><p>承認済みのスキルと得意分野を確認し、変更は申請として登録します</p></div><div className="form-actions"><Link className="secondary-button" to="/talent/new/KNOWLEDGE">得意分野を登録</Link><Link className="primary-button" to="/talent/new/SKILL">スキルを登録</Link></div></div>
    <TalentProfileLoader employeePublicId={user.employeePublicId} errorMessage="スキル情報を取得できませんでした。">{(profile) => <div className="talent-profile"><SkillSection skills={profile.skills} /><KnowledgeSection knowledge={profile.knowledge} /></div>}</TalentProfileLoader>
    <TalentSubmissionStatusPanel types={['SKILL', 'KNOWLEDGE']} />
  </>
}
