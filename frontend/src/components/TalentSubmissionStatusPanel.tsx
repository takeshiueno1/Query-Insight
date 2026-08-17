import { useQueries } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'
import { api } from '../lib/api'
import { talentSubmissionStatusLabels, talentSubmissionTypeLabels, type TalentSubmission, type TalentSubmissionType } from '../types'

export function TalentSubmissionStatusPanel({ types }: { types: TalentSubmissionType[] }) {
  const { user } = useAuth()
  const accountPublicId = user?.accountPublicId ?? ''
  const queries = useQueries({
    queries: types.map((type) => ({
      queryKey: ['talent-submissions', 'mine', accountPublicId, type],
      queryFn: () => api<TalentSubmission[]>(`/api/v1/talent-submissions/me?type=${type}`),
      enabled: Boolean(accountPublicId),
    })),
  })
  if (queries.some((query) => query.isLoading)) return <section className="card" role="status"><p>申請状況を読み込んでいます…</p></section>
  if (queries.some((query) => query.isError)) return <section className="card" role="alert"><p className="error-banner">申請状況を取得できませんでした。</p></section>

  const latest = latestByChain(queries.flatMap((query) => query.data ?? []))
  const inProgress = latest.filter((item) => item.status === 'DRAFT' || item.status === 'SUBMITTED')
  const returned = latest.filter((item) => item.status === 'RETURNED')
  return <section className="card talent-wide">
    <div className="card-header"><div><h2>申請状況</h2><p>下書き、申請中、差戻しを承認済み情報と分けて表示します</p></div><strong>{inProgress.length + returned.length}件</strong></div>
    {inProgress.length > 0 && <SubmissionGroup heading="手続き中" submissions={inProgress} />}
    {returned.length > 0 && <SubmissionGroup heading="差戻し" submissions={returned} />}
    {inProgress.length === 0 && returned.length === 0 && <p className="empty">手続き中または差戻しの申請はありません。</p>}
  </section>
}

function SubmissionGroup({ heading, submissions }: { heading: string; submissions: TalentSubmission[] }) {
  return <div className="workflow-history"><h3>{heading}</h3>{submissions.map((item) => <article key={item.publicId}>
    <div><strong>{talentSubmissionTypeLabels[item.type]} / 第{item.revisionNo}版</strong> <span className={`status ${item.status === 'RETURNED' ? 'danger' : 'warning'}`}>{talentSubmissionStatusLabels[item.status]}</span></div>
    {item.returnReason && <p className="notice">差戻し理由: {item.returnReason}</p>}
    <div className="form-actions">
      {item.status === 'DRAFT' && <Link className="primary-button" to={`/talent/${item.type}/${item.publicId}/edit`}>編集を再開</Link>}
      {item.status === 'RETURNED' && <Link className="primary-button" to={`/talent/${item.type}/${item.publicId}/edit`}>修正して再申請</Link>}
      <Link className="secondary-button" to={`/talent/${item.logicalPublicId}/history`}>履歴を見る</Link>
    </div>
  </article>)}</div>
}

function latestByChain(submissions: TalentSubmission[]) {
  const latest = new Map<string, TalentSubmission>()
  for (const submission of submissions) {
    const current = latest.get(submission.logicalPublicId)
    if (!current || submission.revisionNo > current.revisionNo) latest.set(submission.logicalPublicId, submission)
  }
  return [...latest.values()].sort((left, right) => left.type.localeCompare(right.type) || right.revisionNo - left.revisionNo)
}
