import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { TalentProfile } from '../types'

export function TalentProfilePanel({ employeePublicId }: { employeePublicId: string }) {
  const query = useQuery({
    queryKey: ['talent-profile', employeePublicId],
    queryFn: () => api<TalentProfile>(`/api/v1/employees/${employeePublicId}/talent-profile`),
  })
  if (query.isLoading) return <section className="card"><p>タレントプロフィールを読み込んでいます…</p></section>
  if (!query.data) return <section className="card"><p className="error-banner">タレントプロフィールを取得できませんでした。</p></section>
  const profile = query.data
  return <div className="talent-profile">
    <section className="card">
      <div className="card-header"><div><h2>スキル</h2><p>実務経験と根拠に基づく習熟度</p></div><strong>{profile.skills.length}件</strong></div>
      <div className="tag-grid">{profile.skills.map((skill) => <article key={skill.code}>
        <div><strong>{skill.name}</strong><span>{skill.category}</span></div>
        <Level value={skill.level} />
        <p>経験 {skill.yearsExperience}年 / 最終利用 {skill.lastUsedOn}</p><small>{skill.evidence}</small>
      </article>)}</div>
    </section>
    <section className="card">
      <div className="card-header"><div><h2>専門知識</h2><p>判断や設計に利用できる知識領域</p></div><strong>{profile.knowledge.length}件</strong></div>
      <div className="tag-grid">{profile.knowledge.map((item) => <article key={item.code}>
        <div><strong>{item.name}</strong><span>{item.category}</span></div><Level value={item.level} /><small>{item.evidence}</small>
      </article>)}</div>
    </section>
    <section className="card talent-wide">
      <div className="card-header"><div><h2>業務経験</h2><p>担当範囲・成果・利用技術</p></div><strong>{profile.careers.length}件</strong></div>
      <div className="career-list">{profile.careers.map((career) => <article key={`${career.projectName}-${career.startDate}`}>
        <div><span className="eyebrow">{career.startDate} — {career.endDate ?? '現在'}</span><h3>{career.projectName}</h3><p>{career.industry} / {career.roleName}</p></div>
        <p>{career.summary}</p><strong>成果</strong><p>{career.achievements}</p><small>{career.technologies}</small>
      </article>)}</div>
    </section>
    <section className="card talent-wide">
      <div className="card-header"><div><h2>資格</h2><p>確認済みの保有資格</p></div><strong>{profile.certifications.length}件</strong></div>
      <div className="certification-list">{profile.certifications.map((certification) => <article key={certification.code}>
        <div><strong>{certification.name}</strong><small>{certification.issuer}</small></div>
        <span>{certification.acquiredOn}</span><span className="status success">{certification.verificationStatus}</span>
      </article>)}</div>
    </section>
  </div>
}

function Level({ value }: { value: number }) {
  return <span className="level-meter" aria-label={`レベル${value}`}>{[1, 2, 3, 4, 5].map((level) => <i key={level} className={level <= value ? 'filled' : ''} />)}</span>
}
