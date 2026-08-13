import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { ManagerTalentItem } from '../types'
export function ManagerTalentApprovalsPage() {
  const query = useQuery({ queryKey: ['manager-talent'], queryFn: () => api<ManagerTalentItem[]>('/api/v1/manager/talent-submissions?status=SUBMITTED') })
  return <><div className="page-heading"><div><span className="eyebrow">OFFICER APPROVAL</span><h1>タレント申請承認</h1><p>現在の直属社員から届いた申請を1件ずつ確認します</p></div></div><section className="card table-card"><div className="table-scroll"><table><thead><tr><th>社員</th><th>種類</th><th>提出日時</th><th>操作</th></tr></thead><tbody>{query.data?.map((item) => <tr key={item.publicId}><td>{item.employeeName}</td><td>{item.type}</td><td>{new Date(item.submittedAt).toLocaleString('ja-JP')}</td><td><Link className="text-link" to={`/approvals/talent/${item.publicId}`}>確認</Link></td></tr>)}</tbody></table></div></section></>
}
