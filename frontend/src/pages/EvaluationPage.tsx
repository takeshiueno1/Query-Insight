import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { RadarChart } from '../components/RadarChart'
import { api, ApiError } from '../lib/api'
import type { FinalEvaluationResult, SelfEvaluation } from '../types'

type Draft = Record<string, { level: number; evidence: string }>

export function EvaluationPage() {
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['self-evaluation'], queryFn: () => api<SelfEvaluation>('/api/v1/evaluations/me') })
  const finalQuery = useQuery({ queryKey: ['final-evaluation-result'], queryFn: () => api<FinalEvaluationResult>('/api/v1/evaluations/me/final-result') })
  const [draft, setDraft] = useState<Draft>({})
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => {
    if (query.data) setDraft(Object.fromEntries(query.data.details.map((detail) => [detail.axisCode, { level: detail.level ?? 1, evidence: detail.evidence ?? '' }])))
  }, [query.data])
  const mutation = useMutation({ mutationFn: ({ submit }: { submit: boolean }) => {
    if (!query.data) throw new Error('評価がありません')
    const body = { version: query.data.version, details: query.data.details.map((detail) => ({ axisCode: detail.axisCode, ...draft[detail.axisCode] })) }
    return api<SelfEvaluation>(`/api/v1/evaluations/me${submit ? '/submit' : ''}`, { method: submit ? 'POST' : 'PUT', body: JSON.stringify(body) })
  }, onSuccess: async (_, variables) => { setMessage(variables.submit ? '自己評価を提出しました。' : '下書きを保存しました。'); await client.invalidateQueries({ queryKey: ['self-evaluation'] }) },
  onError: (reason) => setMessage(reason instanceof ApiError ? reason.problem.detail : '保存できませんでした') })
  if (query.isLoading) return <div className="state-card">評価を読み込んでいます…</div>
  if (!query.data) return <div className="state-card"><h1>受付中の自己評価はありません</h1><p>評価期間が開始されるとここに表示されます。</p></div>
  const evaluation = query.data
  const editable = evaluation.status === 'SELF_IN_PROGRESS' || evaluation.status === 'SELF_RETURNED'
  const scores = evaluation.details.map((detail) => ({ axisCode: detail.axisCode, displayName: detail.displayName, level: draft[detail.axisCode]?.level ?? 0 }))
  if (finalQuery.data?.status === 'FINALIZED') return <><div className="page-heading"><div><span className="eyebrow">FINAL RESULT</span><h1>確定評価</h1><p>{evaluation.periodName} / 最終承認済み</p></div><span className="status success">確定</span></div><div className="metric-grid final-result"><article className="metric-card"><span>確定スコア</span><strong>{finalQuery.data.finalScore}</strong></article><article className="metric-card"><span>等級</span><strong>{finalQuery.data.finalGrade}</strong></article><article className="metric-card"><span>評価項目</span><strong>{finalQuery.data.details.length}</strong></article></div><section className="card"><h2>上長総評</h2><p className="summary-text">{finalQuery.data.summary}</p><div className="table-scroll"><table><thead><tr><th>評価軸</th><th>自己評価</th><th>上長評価</th><th>コメント</th></tr></thead><tbody>{finalQuery.data.details.map((d) => <tr key={d.axisCode}><td>{d.displayName}</td><td>{d.selfLevel}</td><td>{d.managerLevel}</td><td>{d.comment || '—'}</td></tr>)}</tbody></table></div></section></>
  return <><div className="page-heading"><div><span className="eyebrow">SCR-009</span><h1>自己評価</h1><p>{evaluation.periodName} / 状態: {evaluation.status}</p></div><span className="status warning">評価期間中</span></div>
    <div className="evaluation-grid"><section className="card sticky-chart"><h2>評価バランス</h2><RadarChart scores={scores} /><p className="notice">等級境界は未承認のため表示しません。各軸の根拠を重視してください。</p></section>
      <section className="card"><h2>評価入力</h2>{!editable && <p className="notice" role="status">提出済みのため、現在は参照のみ可能です。</p>}<div className="axis-list">{evaluation.details.map((detail) => <article className="axis-editor" key={detail.axisCode}><div><h3>{detail.displayName}</h3><p>{detail.description}</p></div><div className="level-buttons" role="group" aria-label={`${detail.displayName}のレベル`}>{[1,2,3,4,5].map((level) => <button type="button" key={level} disabled={!editable} className={draft[detail.axisCode]?.level === level ? 'selected' : ''} onClick={() => setDraft((current) => ({ ...current, [detail.axisCode]: { level, evidence: current[detail.axisCode]?.evidence ?? '' } }))}>{level}</button>)}</div><label>根拠<textarea maxLength={1500} disabled={!editable} value={draft[detail.axisCode]?.evidence ?? ''} onChange={(event) => setDraft((current) => ({ ...current, [detail.axisCode]: { level: current[detail.axisCode]?.level ?? 1, evidence: event.target.value } }))} /></label></article>)}</div>
        {message && <div className={mutation.isError ? 'error-banner' : 'success-banner'} role="status">{message}</div>}
        <div className="form-actions"><button className="secondary-button" disabled={!editable || mutation.isPending} onClick={() => mutation.mutate({ submit: false })}>下書き保存</button><button className="primary-button" disabled={!editable || mutation.isPending} onClick={() => mutation.mutate({ submit: true })}>根拠を確認して提出</button></div>
      </section></div></>
}
