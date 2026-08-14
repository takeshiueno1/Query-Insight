import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { api } from '../lib/api'
import { competencyLevelLabel } from '../lib/competency'
import { talentVerificationStatusLabels, type TalentProfile } from '../types'

export function TalentProfileLoader({ employeePublicId, errorMessage, children }: {
  employeePublicId: string
  errorMessage: string
  children: (profile: TalentProfile) => ReactNode
}) {
  const query = useQuery({
    queryKey: ['talent-profile', employeePublicId],
    queryFn: () => api<TalentProfile>(`/api/v1/employees/${employeePublicId}/talent-profile`),
  })
  if (query.isLoading) return <section className="card" role="status"><p>タレント情報を読み込んでいます…</p></section>
  if (!query.data) return <section className="card" role="alert"><p className="error-banner">{errorMessage}</p></section>
  return children(query.data)
}

export function TalentProfilePanel({ employeePublicId }: { employeePublicId: string }) {
  return <TalentProfileLoader employeePublicId={employeePublicId} errorMessage="タレントプロフィールを取得できませんでした。">{(profile) => <div className="talent-profile">
    <SkillSection skills={profile.skills} />
    <KnowledgeSection knowledge={profile.knowledge} />
    <CareerSection careers={profile.careers} />
    <CertificationSection certifications={profile.certifications} />
  </div>}</TalentProfileLoader>
}

export function SkillSection({ skills }: { skills: TalentProfile['skills'] }) {
  return <section className="card">
      <div className="card-header"><div><h2>スキル</h2><p>実務経験と根拠に基づく習熟状況</p></div><strong>{skills.length}件</strong></div>
      <div className="tag-grid">{skills.map((skill) => <article key={skill.code}>
        <div><strong>{skill.name}</strong><span>{skill.category}</span></div>
        <Level value={skill.level} />
        <p>経験 {skill.yearsExperience}年 / 最終利用 {skill.lastUsedOn}</p><small>{skill.evidence}</small>
      </article>)}{skills.length === 0 && <p className="empty">登録済みのスキルはありません。</p>}</div>
    </section>
}

export function KnowledgeSection({ knowledge }: { knowledge: TalentProfile['knowledge'] }) {
  return <section className="card">
      <div className="card-header"><div><h2>得意分野</h2><p>判断や設計に利用できる知識領域</p></div><strong>{knowledge.length}件</strong></div>
      <div className="tag-grid">{knowledge.map((item) => <article key={item.code}>
        <div><strong>{item.name}</strong><span>{item.category}</span></div><Level value={item.level} /><small>{item.evidence}</small>
      </article>)}{knowledge.length === 0 && <p className="empty">登録済みの得意分野はありません。</p>}</div>
    </section>
}

export function CareerSection({ careers }: { careers: TalentProfile['careers'] }) {
  return <section className="card talent-wide">
      <div className="card-header"><div><h2>業務経歴</h2><p>担当範囲・成果・利用技術</p></div><strong>{careers.length}件</strong></div>
      <div className="career-list">{careers.map((career) => <article key={`${career.projectName}-${career.startDate}`}>
        <div><span className="eyebrow">{career.startDate} — {career.endDate ?? '現在'}</span><h3>{career.projectName}</h3><p>{career.industry} / {career.roleName}</p></div>
        <p>{career.summary}</p><strong>成果</strong><p>{career.achievements}</p><small>{career.technologies}</small>
      </article>)}{careers.length === 0 && <p className="empty">登録済みの業務経歴はありません。</p>}</div>
    </section>
}

export function CertificationSection({ certifications }: { certifications: TalentProfile['certifications'] }) {
  return <section className="card talent-wide">
      <div className="card-header"><div><h2>資格</h2><p>確認済みの保有資格</p></div><strong>{certifications.length}件</strong></div>
      <div className="certification-list">{certifications.map((certification) => <article key={certification.code}>
        <div><strong>{certification.name}</strong><small>{certification.issuer}</small></div>
        <span>{certification.acquiredOn}</span><span className="status success">{talentVerificationStatusLabels[certification.verificationStatus] ?? '確認状態不明'}</span>
      </article>)}{certifications.length === 0 && <p className="empty">登録済みの資格はありません。</p>}</div>
    </section>
}

function Level({ value }: { value: number }) {
  return <span className="level-meter" aria-label={`習熟状況: ${competencyLevelLabel(value)}`}>{[1, 2, 3, 4, 5].map((level) => <i key={level} className={level <= value ? 'filled' : ''} />)}</span>
}
