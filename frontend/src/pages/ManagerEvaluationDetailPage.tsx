import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import type { ManagerEvaluation } from '../types'

type Draft = Record<string, { level: number; comment: string }>

export function ManagerEvaluationDetailPage() {
  const { publicId = '' } = useParams()
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['manager-evaluation', publicId], queryFn: () => api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`) })
  const [draft, setDraft] = useState<Draft>({})
  const [summary, setSummary] = useState('')
  const [reason, setReason] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { if (query.data) { setSummary(query.data.summary ?? ''); setDraft(Object.fromEntries(query.data.details.map((d) => [d.axisCode, { level: d.managerLevel ?? d.selfLevel ?? 1, comment: d.managerComment ?? '' }])))} }, [query.data])
  const mutation = useMutation({ mutationFn: async (action: 'save'|'submit'|'return') => {
    if (!query.data) throw new Error('評価がありません')
    const saveBody = { version: query.data.targetVersion, summary, details: query.data.details.map((d) => ({ axisCode: d.axisCode, ...draft[d.axisCode] })) }
    if (action === 'save') return api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`, { method: 'PUT', body: JSON.stringify(saveBody) })
    if (action === 'submit') {
      const saved = await api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`, { method: 'PUT', body: JSON.stringify(saveBody) })
      return api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}/submit`, { method: 'POST', body: JSON.stringify({ version: saved.targetVersion }) })
    }
    return api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}/return`, { method: 'POST', body: JSON.stringify({ version: query.data.targetVersion, reason }) })
  }, onSuccess: async (_, action) => { setMessage(action === 'save' ? '上長評価を保存しました。' : action === 'submit' ? '社長へ提出しました。' : '本人へ差し戻しました。'); await client.invalidateQueries({ queryKey: ['manager-evaluation', publicId] }); await client.invalidateQueries({ queryKey: ['manager-evaluations'] }) }, onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理を完了できませんでした') })
  if (query.isLoading) return <div className="state-card">評価内容を読み込んでいます…</div>
  if (!query.data) return <div className="state-card">評価が見つかりません。</div>
  const evaluation = query.data
  const editable = ['SELF_SUBMITTED', 'MANAGER_IN_PROGRESS', 'MANAGER_RETURNED'].includes(evaluation.status)
  const submitReady = editable && summary.trim() !== '' && evaluation.details.every((d) => draft[d.axisCode] && (draft[d.axisCode].level === d.selfLevel || draft[d.axisCode].comment.trim() !== ''))
  return <><div className="page-heading"><div><span className="eyebrow">MANAGER REVIEW</span><h1>{evaluation.employeeName}さんの上長評価</h1><p>{evaluation.departmentName ?? '所属未設定'} / {evaluation.periodName}</p></div><div><span className="status warning">{evaluation.status}</span>{evaluation.late && <span className="status danger status-gap">期限超過</span>}</div></div>
    <div className="evaluation-grid"><section className="card sticky-chart"><h2>判断材料</h2><dl className="detail-list"><div><dt>算出スコア</dt><dd>{evaluation.score ?? '保存後に算出'}</dd></div><div><dt>等級</dt><dd>{evaluation.grade ?? '—'}</dd></div></dl><label className="reason-box">本人への差戻し理由<textarea maxLength={1000} disabled={!editable} value={reason} onChange={(e) => setReason(e.target.value)} /></label><button className="secondary-button full" disabled={!editable || !reason.trim() || mutation.isPending} onClick={() => mutation.mutate('return')}>本人へ差し戻す</button><Link className="text-link back-link" to="/evaluations/manager">← 一覧へ戻る</Link></section>
      <section className="card"><h2>自己評価との比較</h2><div className="axis-list">{evaluation.details.map((detail) => <article className="axis-editor comparison-editor" key={detail.axisCode}><div><h3>{detail.displayName}</h3><p>{detail.description}</p><p className="self-evidence"><strong>本人 {detail.selfLevel ?? '—'}</strong>　{detail.selfEvidence || '根拠なし'}</p></div><div className="level-buttons" role="group" aria-label={`${detail.displayName}の上長評価`}>{[1,2,3,4,5].map((level) => <button type="button" key={level} disabled={!editable} className={draft[detail.axisCode]?.level === level ? 'selected' : ''} onClick={() => setDraft((old) => ({ ...old, [detail.axisCode]: { level, comment: old[detail.axisCode]?.comment ?? '' } }))}>{level}</button>)}</div><label>上長コメント{draft[detail.axisCode]?.level !== detail.selfLevel && <span>（本人評価との差分理由は必須）</span>}<textarea maxLength={1500} disabled={!editable} value={draft[detail.axisCode]?.comment ?? ''} onChange={(e) => setDraft((old) => ({ ...old, [detail.axisCode]: { level: old[detail.axisCode]?.level ?? 1, comment: e.target.value } }))} /></label></article>)}</div>
        <label className="summary-box">上長総評 <span>（提出時必須）</span><textarea maxLength={3000} disabled={!editable} value={summary} onChange={(e) => setSummary(e.target.value)} /></label>{message && <div role="status" className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}<div className="form-actions"><button className="secondary-button" disabled={!editable || mutation.isPending} onClick={() => mutation.mutate('save')}>下書き保存</button><button className="primary-button" disabled={!submitReady || mutation.isPending} onClick={() => mutation.mutate('submit')}>社長へ提出</button></div>
      </section></div></>
}
