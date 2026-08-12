import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api } from '../lib/api'
import type { TalentSubmission } from '../types'

export function TalentSubmissionHistoryPage() {
  const { logicalPublicId = '' } = useParams()
  const query = useQuery({ queryKey: ['talent-history', logicalPublicId], queryFn: () => api<TalentSubmission[]>(`/api/v1/talent-submissions/me/${logicalPublicId}/history`) })
  return <><div className="page-heading"><div><span className="eyebrow">VERSION HISTORY</span><h1>申請履歴</h1><p>旧版と差戻し理由を変更せずに確認できます</p></div></div><section className="card workflow-history">{query.data?.map((item) => <article key={item.publicId}><strong>第{item.revisionNo}版 / {item.status}</strong><pre>{JSON.stringify(item.payload, null, 2)}</pre><time>{item.submittedAt ? new Date(item.submittedAt).toLocaleString('ja-JP') : '下書き'}</time>{item.returnReason && <p className="notice">差戻し理由: {item.returnReason}</p>}</article>)}{query.data?.length === 0 && <p className="empty">履歴はありません。</p>}<Link className="text-link back-link" to="/skills/edit">← プロフィールへ戻る</Link></section></>
}
