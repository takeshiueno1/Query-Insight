import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import type { ExecutiveEvaluation } from '../types'

export function ExecutiveEvaluationDetailPage() {
  const { publicId = '' } = useParams()
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['executive-evaluation', publicId], queryFn: () => api<ExecutiveEvaluation>(`/api/v1/executive/evaluations/${publicId}`) })
  const [comment, setComment] = useState('')
  const [reason, setReason] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const mutation = useMutation({ mutationFn: (action: 'approve'|'return'|'reopen') => {
    if (!query.data) throw new Error('評価がありません')
    return api<ExecutiveEvaluation>(`/api/v1/executive/evaluations/${publicId}/${action}`, { method: 'POST', body: JSON.stringify(action === 'approve' ? { version: query.data.targetVersion, comment } : { version: query.data.targetVersion, reason }) })
  }, onSuccess: async (_, action) => { setMessage(action === 'approve' ? '評価を最終承認しました。' : action === 'reopen' ? '確定評価を再オープンしました。' : '上長へ差し戻しました。'); await client.invalidateQueries({ queryKey: ['executive-evaluation', publicId] }); await client.invalidateQueries({ queryKey: ['executive-evaluations'] }) }, onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理を完了できませんでした') })
  if (query.isLoading) return <div className="state-card">評価内容を読み込んでいます…</div>
  if (!query.data) return <div className="state-card">評価が見つかりません。</div>
  const evaluation = query.data
  const reviewable = evaluation.status === 'EXECUTIVE_REVIEW'
  const finalized = evaluation.status === 'FINALIZED'
  return <><div className="page-heading"><div><span className="eyebrow">FINAL DECISION</span><h1>{evaluation.employeeName}さんの最終評価</h1><p>{evaluation.departmentName ?? '所属未設定'} / {evaluation.periodName}</p></div><span className={`status ${finalized ? 'success' : 'warning'}`}>{evaluation.status}</span></div>
    <div className="decision-grid"><section className="card"><h2>評価結果案</h2><div className="decision-score"><strong>{evaluation.score ?? evaluation.finalScore ?? '—'}</strong><span>等級 {evaluation.grade ?? evaluation.finalGrade ?? '—'}</span></div><h3>上長総評</h3><p className="summary-text">{evaluation.summary || '総評はありません。'}</p><label>承認コメント（任意）<textarea maxLength={2000} disabled={!reviewable} value={comment} onChange={(e) => setComment(e.target.value)} /></label><label>差戻し・再オープン理由（必須）<textarea maxLength={1000} disabled={!reviewable && !finalized} value={reason} onChange={(e) => setReason(e.target.value)} /></label>{message && <div role="status" className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}<div className="form-actions">{reviewable && <><button className="secondary-button" disabled={!reason.trim() || mutation.isPending} onClick={() => mutation.mutate('return')}>上長へ差し戻す</button><button className="primary-button" disabled={mutation.isPending} onClick={() => mutation.mutate('approve')}>最終承認</button></>}{finalized && <button className="secondary-button" disabled={!reason.trim() || mutation.isPending} onClick={() => mutation.mutate('reopen')}>理由を記録して再オープン</button>}</div><Link className="text-link back-link" to="/executive/evaluations">← 一覧へ戻る</Link></section>
      <section className="card"><h2>本人・上長評価の差分</h2><div className="comparison-list">{evaluation.details.map((detail) => <article key={detail.axisCode}><div><strong>{detail.displayName}</strong><small>本人 {detail.selfLevel ?? '—'} → 上長 {detail.managerLevel ?? '—'}</small></div><p>{detail.managerComment || '差分コメントなし'}</p></article>)}</div></section>
      <section className="card workflow-history"><h2>判断履歴</h2>{evaluation.events.map((event) => <article key={`${event.action}-${event.occurredAt}`}><strong>{event.action}</strong><span>{event.fromStatus} → {event.toStatus}</span><time>{new Date(event.occurredAt).toLocaleString('ja-JP')}</time>{event.reason && <p>{event.reason}</p>}{event.comment && <p>{event.comment}</p>}</article>)}</section></div></>
}
