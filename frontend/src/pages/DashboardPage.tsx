import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AnalysisPanel } from '../components/AnalysisPanel'
import { RadarChart } from '../components/RadarChart'
import { RankBadge } from '../components/RankBadge'
import { useAuth } from '../features/auth/auth-context'
import { api } from '../lib/api'
import type { Dashboard, FinalManagerEvaluation, ProfileStatus, Score } from '../types'

const categoryLabels = {
  SKILL: 'スキル', KNOWLEDGE: '得意分野', CAREER: '業務経歴', CERTIFICATION: '資格',
} as const

export function DashboardPage() {
  const { user } = useAuth()
  const query = useQuery({
    queryKey: ['dashboard', user?.accountPublicId],
    queryFn: () => api<Dashboard>('/api/v1/dashboard/me'),
    enabled: Boolean(user?.accountPublicId),
    retry: false,
    throwOnError: false,
  })
  if (query.isLoading) return <PageState kind="loading" />
  if (query.isError || !query.data) return <PageState kind="error" />
  const { profile, profileStatus, finalManagerEvaluation, unreadNotifications } = query.data
  const balanceScores = profileStatusScores(profileStatus)

  return <>
    <div className="page-heading">
      <div><h1>個人ダッシュボード</h1><p>{profile.name}さんの最新状況</p></div>
      <span className="updated">プロフィール更新 {new Date(profile.updatedAt).toLocaleDateString('ja-JP')}</span>
    </div>
    <section className="metric-grid dashboard-metrics" aria-label="プロフィール概要">
      <article className="metric-card"><span>プロフィール</span><strong>{profile.employeeNo}</strong><small>{profile.department ?? '所属未設定'} / {profile.positionName ?? '役職未設定'}</small></article>
      <article className="metric-card"><span>自己ステータス</span><strong>{profileStatus.grade} / {formatScore(profileStatus.totalScore)}点</strong><small>承認済み情報から自動算出</small></article>
      <article className="metric-card"><span>通知</span><strong aria-label={`未読通知 ${unreadNotifications}件`}>未読通知 {unreadNotifications}件</strong><Link to="/notifications">通知を確認</Link></article>
    </section>
    <div className="dashboard-primary-grid">
      <ProfileStatusCard status={profileStatus} />
      <FinalEvaluationCard evaluation={finalManagerEvaluation} />
      <section className="card dashboard-balance">
        <div className="card-header"><div><h2>能力バランス</h2><p>直属上長が承認し、正式反映された4分野の点数です。</p></div></div>
        <RadarChart scores={balanceScores} maxValue={100} title="承認済み情報の能力バランス" valueLabel="点数" emptyLabel="未登録" />
      </section>
    </div>
    <AnalysisPanel />
  </>
}

function ProfileStatusCard({ status }: { status: ProfileStatus }) {
  const missing = status.missingCategories.map((category) => categoryLabels[category])
  return <section className="card profile-status-card" aria-labelledby="profile-status-heading">
    <div className="card-header">
      <div><h2 id="profile-status-heading">自己ステータス</h2><p>本人による編集はできません。</p></div>
      <RankBadge rank={status.grade} label="自己ステータスのランク" />
    </div>
    <div className="profile-total"><strong>{formatScore(status.totalScore)}点</strong><span>総合点</span></div>
    <dl className="status-score-grid">
      {profileStatusScores(status).map((score) => <div key={score.axisCode}>
        <dt>{score.displayName}</dt><dd>{formatScore(score.level)}点</dd>
      </div>)}
    </dl>
    <p className="missing-categories">{missing.length > 0 ? `未登録: ${missing.join('、')}` : '未登録分野はありません'}</p>
    <div className="formula-note">
      <p>算出式: スキル40% + 得意分野20% + 業務経歴25% + 資格15%</p>
      <p>算出日時: <time dateTime={status.calculatedAt}>{new Date(status.calculatedAt).toLocaleString('ja-JP')}</time> / 算出方式 {formulaLabel(status.formulaVersion)}</p>
    </div>
  </section>
}

function FinalEvaluationCard({ evaluation }: { evaluation: FinalManagerEvaluation | null }) {
  return <section className="card final-evaluation-card" aria-labelledby="final-evaluation-heading">
    <div className="card-header">
      <div><h2 id="final-evaluation-heading">公開済み上長評価</h2><p>最終承認後に公開された内容だけを表示します。</p></div>
      {evaluation && <RankBadge rank={evaluation.finalRank} label="上長評価の総合ランク" />}
    </div>
    {!evaluation && <div className="dashboard-empty" role="status" aria-label="上長評価の公開状況">公開済みの上長評価はありません</div>}
    {evaluation && <>
      {evaluation.summary && <p className="manager-summary">{evaluation.summary}</p>}
      <div className="manager-rank-list">{evaluation.details.map((detail) => <article key={detail.axisCode}>
        <div><h3>{detail.displayName}</h3><RankBadge rank={detail.managerRank} label={`${detail.displayName}のランク`} /></div>
        <p>{detail.comment ?? 'コメントはありません'}</p>
      </article>)}</div>
      <p className="finalized-at">公開日時: <time dateTime={evaluation.finalizedAt}>{new Date(evaluation.finalizedAt).toLocaleString('ja-JP')}</time></p>
    </>}
  </section>
}

function profileStatusScores(status: ProfileStatus): Score[] {
  return [
    { axisCode: 'SKILL', displayName: 'スキル', level: status.skillScore },
    { axisCode: 'KNOWLEDGE', displayName: '得意分野', level: status.knowledgeScore },
    { axisCode: 'CAREER', displayName: '業務経歴', level: status.careerScore },
    { axisCode: 'CERTIFICATION', displayName: '資格', level: status.certificationScore },
  ]
}

function formatScore(value: number) {
  return value.toFixed(1)
}

function formulaLabel(formulaVersion: string) {
  return formulaVersion === 'PROFILE_STATUS_V1' ? '第1版' : '更新版'
}

function PageState({ kind }: { kind: 'loading' | 'error' }) {
  if (kind === 'loading') return <div className="state-card" role="status" aria-label="ダッシュボード読込中" aria-live="polite" aria-busy="true">
    <div className="spinner" aria-hidden="true" /><h1>ダッシュボードを読み込んでいます</h1>
  </div>
  return <div className="state-card" role="alert"><h1>ダッシュボードを表示できません</h1><p>時間をおいて再読み込みしてください。</p></div>
}
