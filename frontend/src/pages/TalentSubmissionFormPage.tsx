import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import type { TalentSubmission, TalentSubmissionType } from '../types'

const labels: Record<TalentSubmissionType, string> = { SKILL: 'スキル', KNOWLEDGE: '専門知識', CAREER: '業務経歴', CERTIFICATION: '資格' }

export function TalentSubmissionFormPage() {
  const { type = 'SKILL' } = useParams()
  const talentType = (Object.keys(labels).includes(type.toUpperCase()) ? type.toUpperCase() : 'SKILL') as TalentSubmissionType
  const [payloadText, setPayloadText] = useState('{}')
  const [draft, setDraft] = useState<TalentSubmission | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const client = useQueryClient()
  const parsed = useMemo(() => { try { return JSON.parse(payloadText) as Record<string, unknown> } catch { return null } }, [payloadText])
  const mutation = useMutation({ mutationFn: async (action: 'save'|'submit') => {
    if (!parsed) throw new Error('JSON形式を確認してください')
    let current = draft
    current = current
      ? await api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}`, { method: 'PUT', body: JSON.stringify({ version: current.version, payload: parsed }) })
      : await api<TalentSubmission>(`/api/v1/talent-submissions/${talentType}`, { method: 'POST', body: JSON.stringify({ payload: parsed }) })
    for (const file of files) {
      const form = new FormData(); form.append('file', file); form.append('version', String(current.version))
      const uploaded: { submissionVersion: number } = await api<{ submissionVersion: number }>(`/api/v1/talent-submissions/${current.publicId}/attachments`, { method: 'POST', body: form })
      current = { ...current, version: uploaded.submissionVersion }
    }
    return action === 'submit' ? api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}/submit`, { method: 'POST', body: JSON.stringify({ version: current.version }) }) : current
  }, onSuccess: async (value, action) => { setDraft(value); setFiles([]); setMessage(action === 'submit' ? '直属上長へ申請しました。' : '下書きを保存しました。'); await client.invalidateQueries({ queryKey: ['talent-submissions'] }) }, onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : '処理できませんでした') })
  const selectFiles = (list: FileList | null) => { const selected = Array.from(list ?? []); setMessage(null); if (selected.length > 3 || selected.some((f) => f.size > 5_242_880)) { setMessage('添付は3件まで、1件5MB以内です。'); return } setFiles(selected) }
  return <><div className="page-heading"><div><span className="eyebrow">TALENT APPLICATION</span><h1>{labels[talentType]}を申請</h1><p>JSONを保存した後、任意の根拠ファイルを添付して直属上長へ提出します</p></div></div>
    <section className="card"><label>申請内容（JSON）<textarea rows={12} value={payloadText} onChange={(e) => setPayloadText(e.target.value)} /></label><label className="reason-box">根拠ファイル（PDF/JPEG/PNG、3件まで）<input type="file" accept=".pdf,.jpg,.jpeg,.png" multiple onChange={(e) => selectFiles(e.target.files)} /></label>{draft && <p className="notice">状態: {draft.status} / 版: {draft.revisionNo}</p>}{message && <div role="status" className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}<div className="form-actions"><Link className="secondary-button" to="/skills/edit">戻る</Link><button className="secondary-button" disabled={!parsed || mutation.isPending} onClick={() => mutation.mutate('save')}>下書き保存</button><button className="primary-button" disabled={!parsed || mutation.isPending} onClick={() => mutation.mutate('submit')}>直属上長へ申請</button></div></section></>
}
