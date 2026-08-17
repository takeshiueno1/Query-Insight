import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { talentSubmissionTypeLabels, type ManagerTalentItem } from '../types'
export function ManagerTalentApprovalsPage() {
  const query = useQuery({ queryKey: ['manager-talent'], queryFn: () => api<ManagerTalentItem[]>('/api/v1/manager/talent-submissions?status=SUBMITTED') })
  return <><div className="page-heading"><div><span className="eyebrow">直属社員の申請</span><h1>タレント申請承認</h1><p>現在の直属社員から届いた申請を1件ずつ確認します</p></div></div>{query.isLoading
    ? <section className="card" role="status">タレント申請を読み込んでいます…</section>
    : query.isError
      ? <section className="card" role="alert"><p className="error-banner">タレント申請を取得できませんでした。</p></section>
      : query.data?.length === 0
        ? <section className="card"><p className="empty">確認待ちのタレント申請はありません。</p></section>
        : <section className="card table-card"><div className="table-scroll"><table><thead><tr><th>社員</th><th>種類</th><th>提出日時</th><th>操作</th></tr></thead><tbody>{query.data?.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{talentSubmissionTypeLabels[item.type]}</td><td>{new Date(item.submittedAt).toLocaleString('ja-JP')}</td><td><Link className="text-link" to={`/approvals/talent/${item.publicId}`}>確認</Link></td></tr>)}</tbody></table></div></section>}</>
}
