import { useQuery } from '@tanstack/react-query'
import { ApiError, api } from '../lib/api'

type Audit = { publicId: string; occurredAt: string; actorPublicId: string | null; action: string; targetType: string; targetPublicId: string | null; result: string; dataScope: string | null; traceId: string }

const actionLabels: Record<string, string> = {
  AUTH_LOGIN: 'ログイン', AUTH_REFRESH: '認証情報の更新', AUTH_LOGOUT: 'ログアウト',
  AI_ANALYSIS_CREATED: 'AI育成助言の作成',
  EVALUATION_SELF_SUBMIT: '旧本人評価の提出', EVALUATION_MANAGER_SAVE: '上長評価の保存',
  EVALUATION_MANAGER_SUBMIT: '上長評価の提出', EVALUATION_EXECUTIVE_APPROVE: '上長評価の最終承認',
  EVALUATION_EXECUTIVE_RETURN: '上長評価の差戻し', EVALUATION_EXECUTIVE_REOPEN: '確定評価の再オープン',
  MASTER_REQUEST_SUBMIT: 'マスタ申請', MASTER_REQUEST_APPROVE: 'マスタ申請の承認', MASTER_REQUEST_RETURN: 'マスタ申請の差戻し',
  TALENT_CREATE: 'タレント申請の作成', TALENT_SAVE: 'タレント申請の保存', TALENT_SUBMIT: 'タレント申請の提出',
  TALENT_APPROVE: 'タレント申請の承認', TALENT_RETURN: 'タレント申請の差戻し',
}
const targetLabels: Record<string, string> = {
  ACCOUNT: 'アカウント', EVALUATION_TARGET: '評価対象', MASTER_REQUEST: 'マスタ申請', TALENT_SUBMISSION: 'タレント申請',
}
const resultLabels: Record<string, string> = { SUCCESS: '成功', DENIED: '拒否' }
const scopeLabels: Record<string, string> = { SELF: '本人', SUBORDINATES: '直属部下', ALL: '全社' }

export function AuditPage() {
  const query = useQuery({ queryKey: ['audit'], queryFn: () => api<Audit[]>('/api/v1/audit-logs'), throwOnError: false })
  const problem = query.error instanceof ApiError ? query.error.problem : null
  return <><div className="page-heading"><div><span className="eyebrow">管理・監査</span><h1>監査ログ</h1><p>認証・閲覧・更新の追跡記録</p></div></div>
    {query.isError && <div className="error-banner" role="alert">{problem?.detail ?? '監査ログを取得できませんでした'}{problem?.traceId && <>（問い合わせID: {problem.traceId}）</>}</div>}
    <section className="card table-card"><div className="table-scroll"><table><thead><tr><th>日時</th><th>操作</th><th>対象</th><th>結果</th><th>スコープ</th><th>問い合わせID</th></tr></thead><tbody>{query.data?.map((item) => <tr key={item.publicId}><td>{new Date(item.occurredAt).toLocaleString('ja-JP')}</td><td>{actionLabels[item.action] ?? '未定義の操作'}</td><td>{targetLabels[item.targetType] ?? '未定義の対象'}</td><td><span className={`status ${item.result === 'SUCCESS' ? 'success' : 'danger'}`}>{resultLabels[item.result] ?? '不明'}</span></td><td>{item.dataScope ? scopeLabels[item.dataScope] ?? '不明' : '-'}</td><td><code>{item.traceId}</code></td></tr>)}</tbody></table>{!query.isLoading && !query.isError && query.data?.length === 0 && <p className="empty">監査記録はありません。</p>}</div></section></>
}
