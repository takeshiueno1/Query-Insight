import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { EvaluationCriteriaNotice } from '../components/EvaluationCriteriaNotice'
import { RankBadge } from '../components/RankBadge'
import { useAuth } from '../features/auth/auth-context'
import { api, ApiError } from '../lib/api'
import { evaluationStatusLabels, type ExecutiveEvaluation } from '../types'

const actionLabels: Record<string, string> = {
  MANAGER_SAVE: '上長評価を保存',
  MANAGER_SUBMIT: '最終承認者へ提出',
  EXECUTIVE_APPROVE: '最終承認',
  EXECUTIVE_RETURN: '上長へ差戻し',
  EXECUTIVE_REOPEN: '確定評価を再オープン',
}

export function ExecutiveEvaluationDetailPage() {
  const { publicId = '' } = useParams()
  const { user } = useAuth()
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['executive-evaluation', user?.accountPublicId, publicId], queryFn: () => api<ExecutiveEvaluation>(`/api/v1/executive/evaluations/${publicId}`), enabled: Boolean(user?.accountPublicId && publicId) })
  const [comment, setComment] = useState('')
  const [reason, setReason] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (action: 'approve' | 'return' | 'reopen') => {
      if (!query.data) throw new Error('評価がありません')
      return api<ExecutiveEvaluation>(`/api/v1/executive/evaluations/${publicId}/${action}`, { method: 'POST', body: JSON.stringify(action === 'approve' ? { version: query.data.targetVersion, comment } : { version: query.data.targetVersion, reason }) })
    },
    onSuccess: async (_, action) => {
      setMessage(action === 'approve' ? '評価を最終承認しました。' : action === 'reopen' ? '確定評価を再オープンしました。' : '上長へ差し戻しました。')
      await client.invalidateQueries({ queryKey: ['executive-evaluation', user?.accountPublicId, publicId] })
      await client.invalidateQueries({ queryKey: ['executive-evaluations', user?.accountPublicId] })
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理を完了できませんでした'),
  })

  if (query.isLoading) return <div className="state-card" role="status" aria-label="最終評価を読込中" aria-busy="true">評価内容を読み込んでいます…</div>
  if (query.isError) return <div className="state-card" role="alert"><h1>評価内容を取得できませんでした</h1><button className="secondary-button" onClick={() => void query.refetch()}>再読み込み</button></div>
  if (!query.data) return <div className="state-card" role="status">評価が見つかりません。</div>

  const evaluation = query.data
  const reviewable = evaluation.status === 'EXECUTIVE_REVIEW'
  const finalized = evaluation.status === 'FINALIZED'
  const overallRank = evaluation.grade ?? evaluation.finalGrade

  return <>
    <div className="page-heading"><div><h1>{evaluation.employeeName}さんの最終評価</h1><p>{evaluation.departmentName ?? '所属未設定'} / {evaluation.periodName}</p></div><span className={`status ${finalized ? 'success' : 'warning'}`}>{evaluationStatusLabels[evaluation.status] ?? '確認中'}</span></div>
    <div className="decision-grid">
      <section className="card">
        <h2>評価結果案</h2>
        <div className="manager-overall-rank"><span>総合ランク</span>{overallRank ? <RankBadge rank={overallRank} label="上長評価の総合ランク" /> : <strong>未確定</strong>}</div>
        <h3>上長総評</h3><p className="summary-text">{evaluation.summary || '総評はありません。'}</p>
        <label>承認コメント（任意）<textarea maxLength={2000} disabled={!reviewable} value={comment} onChange={(event) => setComment(event.target.value)} /></label>
        <label>差戻し・再オープン理由（必須）<textarea maxLength={1000} disabled={!reviewable && !finalized} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
        {message && <div role={mutation.isError ? 'alert' : 'status'} className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
        <div className="form-actions">{reviewable && <><button className="secondary-button" disabled={!reason.trim() || mutation.isPending} onClick={() => mutation.mutate('return')}>上長へ差し戻す</button><button className="primary-button" disabled={mutation.isPending} onClick={() => mutation.mutate('approve')}>最終承認</button></>}{finalized && <button className="secondary-button" disabled={!reason.trim() || mutation.isPending} onClick={() => mutation.mutate('reopen')}>理由を記録して再オープン</button>}</div>
        <Link className="text-link back-link" to="/executive/evaluations">← 一覧へ戻る</Link>
      </section>
      <section className="card">
        <EvaluationCriteriaNotice />
        <h2>上長評価の内容</h2>
        <div className="comparison-list">{evaluation.details.map((detail) => <article key={detail.axisCode}><div><strong>{detail.displayName}</strong>{detail.managerRank ? <RankBadge rank={detail.managerRank} label={`${detail.displayName}の評価ランク`} /> : <span>未入力</span>}</div><p>{detail.managerComment || 'コメントはありません。'}</p></article>)}</div>
      </section>
      <section className="card workflow-history"><h2>判断履歴</h2>{evaluation.events.map((event) => <article key={`${event.action}-${event.occurredAt}`}><strong>{actionLabels[event.action] ?? '評価操作'}</strong><span>{evaluationStatusLabels[event.fromStatus] ?? '開始'} → {evaluationStatusLabels[event.toStatus] ?? '更新'}</span><time>{new Date(event.occurredAt).toLocaleString('ja-JP')}</time>{event.reason && <p>{event.reason}</p>}{event.comment && <p>{event.comment}</p>}</article>)}</section>
    </div>
  </>
}
