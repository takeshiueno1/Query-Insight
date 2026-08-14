import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { TalentSubmissionDetails } from '../components/TalentSubmissionDetails'
import { api } from '../lib/api'
import {
  talentCategoryRoutes,
  talentSubmissionStatusLabels,
  talentSubmissionTypeLabels,
  type TalentMasterChoice,
  type TalentSubmission,
} from '../types'

export function TalentSubmissionHistoryPage() {
  const { logicalPublicId = '' } = useParams()
  const query = useQuery({ queryKey: ['talent-history', logicalPublicId], queryFn: () => api<TalentSubmission[]>(`/api/v1/talent-submissions/me/${logicalPublicId}/history`) })
  const historyType = query.data?.[0]?.type
  const mastersQuery = useQuery({
    queryKey: ['talent-masters', historyType],
    queryFn: () => api<TalentMasterChoice[]>(`/api/v1/talent-masters/${historyType}`),
    enabled: Boolean(historyType && historyType !== 'CAREER'),
  })

  if (query.isLoading) return <section className="card" role="status">申請履歴を読み込んでいます。</section>
  if (query.isError) return <section className="card" role="alert">申請履歴を読み込めませんでした。</section>

  const backRoute = historyType ? talentCategoryRoutes[historyType] : '/skills'
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">申請の版履歴</span>
        <h1>申請履歴</h1>
        <p>旧版と差戻し理由を変更せずに確認できます</p>
      </div>
    </div>
    <section className="card workflow-history">
      {query.data?.map((item) => <article key={item.publicId}>
        <strong>{talentSubmissionTypeLabels[item.type]} / 第{item.revisionNo}版 / {talentSubmissionStatusLabels[item.status]}</strong>
        <TalentSubmissionDetails type={item.type} payload={item.payload} masters={mastersQuery.data} />
        <time>{item.submittedAt ? new Date(item.submittedAt).toLocaleString('ja-JP') : '下書き保存'}</time>
        {item.returnReason && <p className="notice">差戻し理由: {item.returnReason}</p>}
      </article>)}
      {query.data?.length === 0 && <p className="empty">履歴はありません。</p>}
      <Link className="text-link back-link" to={backRoute}>← 一覧へ戻る</Link>
    </section>
  </>
}
