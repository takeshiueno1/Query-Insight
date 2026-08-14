import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { EvaluationCriteriaNotice } from '../components/EvaluationCriteriaNotice'
import { RankBadge } from '../components/RankBadge'
import { useAuth } from '../features/auth/auth-context'
import { api, ApiError } from '../lib/api'
import { evaluationStatusLabels, type EvaluationRank, type ManagerEvaluation } from '../types'

const ranks: EvaluationRank[] = ['S', 'A', 'B', 'C', 'D', 'F']
type Draft = Record<string, { rank: EvaluationRank | null; comment: string }>

export function ManagerEvaluationDetailPage() {
  const { publicId = '' } = useParams()
  const { user } = useAuth()
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['manager-evaluation', user?.accountPublicId, publicId], queryFn: () => api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`), enabled: Boolean(user?.accountPublicId && publicId) })
  const [draft, setDraft] = useState<Draft>({})
  const [summary, setSummary] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => {
    if (!query.data) return
    setSummary(query.data.summary ?? '')
    setDraft(Object.fromEntries(query.data.details.map((detail) => [detail.axisCode, {
      rank: detail.managerRank,
      comment: detail.managerComment ?? '',
    }])))
  }, [query.data])

  const mutation = useMutation({
    mutationFn: async (action: 'save' | 'submit') => {
      if (!query.data) throw new Error('評価がありません')
      const saveBody = {
        version: query.data.targetVersion,
        summary,
        details: query.data.details.map((detail) => ({
          axisCode: detail.axisCode,
          rank: draft[detail.axisCode]?.rank,
          comment: draft[detail.axisCode]?.comment ?? '',
        })),
      }
      if (action === 'save') {
        const saved = await api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`, { method: 'PUT', body: JSON.stringify(saveBody) })
        client.setQueryData(['manager-evaluation', user?.accountPublicId, publicId], saved)
        return saved
      }
      if (action === 'submit') {
        const saved = await api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}`, { method: 'PUT', body: JSON.stringify(saveBody) })
        client.setQueryData(['manager-evaluation', user?.accountPublicId, publicId], saved)
        const submitted = await api<ManagerEvaluation>(`/api/v1/manager-evaluations/${publicId}/submit`, { method: 'POST', body: JSON.stringify({ version: saved.targetVersion }) })
        client.setQueryData(['manager-evaluation', user?.accountPublicId, publicId], submitted)
        return submitted
      }
      throw new Error('未対応の操作です')
    },
    onSuccess: async (_, action) => {
      setMessage(action === 'save' ? '上長評価を保存しました。' : '最終承認者へ提出しました。')
      await client.invalidateQueries({ queryKey: ['manager-evaluations', user?.accountPublicId] })
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理を完了できませんでした'),
  })

  if (query.isLoading) return <div className="state-card" role="status" aria-label="上長評価を読込中" aria-busy="true">評価内容を読み込んでいます…</div>
  if (query.isError) return <div className="state-card" role="alert"><h1>評価内容を取得できませんでした</h1><button className="secondary-button" onClick={() => void query.refetch()}>再読み込み</button></div>
  if (!query.data) return <div className="state-card" role="status">評価が見つかりません。</div>

  const evaluation = query.data
  const editable = ['DRAFT', 'SELF_RETURNED', 'SELF_SUBMITTED', 'MANAGER_IN_PROGRESS', 'MANAGER_RETURNED'].includes(evaluation.status)
  const allRanksSelected = evaluation.details.every((detail) => draft[detail.axisCode]?.rank)
  const saveReady = editable && allRanksSelected
  const submitReady = saveReady && summary.trim() !== '' && evaluation.details.every((detail) => draft[detail.axisCode]?.comment.trim())

  return <>
    <div className="page-heading">
      <div><h1>{evaluation.employeeName}さんの上長評価</h1><p>{evaluation.departmentName ?? '所属未設定'} / {evaluation.periodName}</p></div>
      <div><span className="status warning">{evaluationStatusLabels[evaluation.status] ?? '確認中'}</span>{evaluation.late && <span className="status danger status-gap">期限超過</span>}</div>
    </div>
    <div className="evaluation-grid">
      <section className="card sticky-chart">
        <h2>判断と操作</h2>
        <div className="manager-overall-rank"><span>総合ランク</span>{evaluation.grade ? <RankBadge rank={evaluation.grade} label="上長評価の総合ランク" /> : <strong>保存・提出後に算出</strong>}</div>
        <Link className="text-link back-link" to="/evaluations/manager">← 一覧へ戻る</Link>
      </section>
      <section className="card">
        <EvaluationCriteriaNotice />
        <h2>評価入力</h2>
        <div className="axis-list">{evaluation.details.map((detail) => <article className="axis-editor rank-axis-editor" key={detail.axisCode}>
          <div><h3>{detail.displayName}</h3><p>{detail.description}</p></div>
          <fieldset className="rank-selector"><legend>{detail.displayName}の上長評価</legend>{ranks.map((rank) => <label key={rank}>
            <input type="radio" name={`${detail.axisCode}-rank`} value={rank} disabled={!editable} checked={draft[detail.axisCode]?.rank === rank} onChange={() => setDraft((old) => ({ ...old, [detail.axisCode]: { rank, comment: old[detail.axisCode]?.comment ?? '' } }))} />
            <span>{rank}</span>
          </label>)}</fieldset>
          <label>上長コメント <span>（提出時必須）</span><textarea maxLength={1500} disabled={!editable} value={draft[detail.axisCode]?.comment ?? ''} onChange={(event) => setDraft((old) => ({ ...old, [detail.axisCode]: { rank: old[detail.axisCode]?.rank ?? null, comment: event.target.value } }))} /></label>
        </article>)}</div>
        <label className="summary-box">上長総評 <span>（提出時必須）</span><textarea maxLength={3000} disabled={!editable} value={summary} onChange={(event) => setSummary(event.target.value)} /></label>
        {message && <div role={mutation.isError ? 'alert' : 'status'} className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
        <div className="form-actions"><button className="secondary-button" disabled={!saveReady || mutation.isPending} onClick={() => mutation.mutate('save')}>下書き保存</button><button className="primary-button" disabled={!submitReady || mutation.isPending} onClick={() => mutation.mutate('submit')}>最終承認者へ提出</button></div>
      </section>
    </div>
  </>
}
