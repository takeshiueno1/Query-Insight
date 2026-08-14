import { useQuery } from '@tanstack/react-query'
import { ApiError, api } from '../lib/api'

type Audit = { publicId: string; occurredAt: string; actorPublicId: string | null; action: string; targetType: string; targetPublicId: string | null; result: string; dataScope: string | null; traceId: string }

export function AuditPage() {
  const query = useQuery({ queryKey: ['audit'], queryFn: () => api<Audit[]>('/api/v1/audit-logs'), throwOnError: false })
  const problem = query.error instanceof ApiError ? query.error.problem : null
  return <><div className="page-heading"><div><span className="eyebrow">管理・監査</span><h1>監査ログ</h1><p>認証・閲覧・更新の追跡記録</p></div></div>
    {query.isError && <div className="error-banner" role="alert">{problem?.detail ?? '監査ログを取得できませんでした'}{problem?.traceId && <>（問い合わせID: {problem.traceId}）</>}</div>}
    <section className="card table-card"><div className="table-scroll"><table><thead><tr><th>日時</th><th>操作</th><th>対象</th><th>結果</th><th>スコープ</th><th>問い合わせID</th></tr></thead><tbody>{query.data?.map((item) => <tr key={item.publicId}><td>{new Date(item.occurredAt).toLocaleString('ja-JP')}</td><td>{item.action}</td><td>{item.targetType}</td><td><span className={`status ${item.result === 'SUCCESS' ? 'success' : 'danger'}`}>{item.result}</span></td><td>{item.dataScope ?? '-'}</td><td><code>{item.traceId}</code></td></tr>)}</tbody></table>{!query.isLoading && !query.isError && query.data?.length === 0 && <p className="empty">監査記録はありません。</p>}</div></section></>
}
