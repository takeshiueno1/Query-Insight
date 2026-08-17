import { useQuery } from '@tanstack/react-query'
import { RankBadge } from '../components/RankBadge'
import { useAuth } from '../features/auth/auth-context'
import { api, ApiError } from '../lib/api'
import type { FinalManagerEvaluation } from '../types'

export function FinalManagerEvaluationPage() {
  const { user } = useAuth()
  const query = useQuery({
    queryKey: ['final-manager-evaluation', user?.accountPublicId],
    queryFn: () => api<FinalManagerEvaluation>('/api/v1/evaluations/me/final-result'),
    enabled: Boolean(user?.accountPublicId),
  })

  if (query.isLoading) {
    return <div className="state-card" role="status" aria-label="上長評価を読込中" aria-busy="true">上長評価を読み込んでいます…</div>
  }
  if (query.isError) {
    if (query.error instanceof ApiError && query.error.problem.status === 404) {
      return <EmptyFinalEvaluation />
    }
    return <div className="state-card" role="alert"><h1>上長評価を表示できませんでした</h1><p>時間をおいて再度お試しください。</p><button className="secondary-button" onClick={() => void query.refetch()}>再読み込み</button></div>
  }
  if (!query.data || query.data.status !== 'FINALIZED') return <EmptyFinalEvaluation />

  const evaluation = query.data
  return <>
    <div className="page-heading">
      <div><h1>上長評価</h1><p>最終承認後に公開された内容だけを表示します。</p></div>
      <span className="status success">確定済み</span>
    </div>
    <section className="card final-manager-summary">
      <div><h2>総合ランク</h2><RankBadge rank={evaluation.finalRank} label="上長評価の総合ランク" /></div>
      <div><h2>上長総評</h2><p className="summary-text">{evaluation.summary || '公開された総評はありません。'}</p></div>
    </section>
    <section className="card">
      <h2>評価項目</h2>
      <div className="final-manager-details">
        {evaluation.details.map((detail) => <article key={detail.axisCode}>
          <div><h3>{detail.displayName}</h3><RankBadge rank={detail.managerRank} label={`${detail.displayName}の評価ランク`} /></div>
          <p>{detail.comment || '公開されたコメントはありません。'}</p>
        </article>)}
      </div>
      <p className="finalized-at">確定日時: {new Date(evaluation.finalizedAt).toLocaleString('ja-JP')}</p>
    </section>
  </>
}

function EmptyFinalEvaluation() {
  return <div className="state-card" role="status" aria-label="上長評価の公開状況"><h1>上長評価</h1><p>公開済みの上長評価はありません</p></div>
}
