import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'
import { api, ApiError } from '../lib/api'
import type { TalentMasterChoice, TalentSubmission, TalentSubmissionType } from '../types'

const labels: Record<TalentSubmissionType, string> = {
  SKILL: 'スキル', KNOWLEDGE: '専門知識', CAREER: '業務経歴', CERTIFICATION: '資格',
}

type FormValues = {
  masterPublicId: string; level: string; yearsExperience: string; lastUsedOn: string; evidence: string
  projectName: string; industry: string; roleName: string; startDate: string; endDate: string
  summary: string; achievements: string; technologies: string; acquiredOn: string; expiresOn: string
  credentialReference: string
}

const required = (label: string, max: number) => z.string().trim().min(1, `${label}を入力してください`).max(max)
const schemas = {
  SKILL: z.object({ masterPublicId: required('スキル', 26), level: z.coerce.number().int().min(1).max(5), yearsExperience: z.coerce.number().min(0).max(60), lastUsedOn: required('最終利用日', 10), evidence: required('根拠', 1000) }),
  KNOWLEDGE: z.object({ masterPublicId: required('専門知識', 26), level: z.coerce.number().int().min(1).max(5), evidence: required('根拠', 1000) }),
  CAREER: z.object({ projectName: required('案件名', 150), industry: required('業界', 100), roleName: required('役割', 100), startDate: required('開始日', 10), endDate: z.string(), summary: required('概要', 1000), achievements: required('成果', 1500), technologies: required('利用技術', 1000) }),
  CERTIFICATION: z.object({ masterPublicId: required('資格', 26), acquiredOn: required('取得日', 10), expiresOn: z.string(), credentialReference: z.string().max(100) }),
} satisfies Record<TalentSubmissionType, z.ZodType>

export function TalentSubmissionFormPage() {
  const { type = 'SKILL' } = useParams()
  const talentType = (Object.keys(labels).includes(type.toUpperCase()) ? type.toUpperCase() : 'SKILL') as TalentSubmissionType
  const { register, handleSubmit } = useForm<FormValues>({ defaultValues: { level: '3', yearsExperience: '0' } })
  const [draft, setDraft] = useState<TalentSubmission | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const client = useQueryClient()
  const masterQuery = useQuery({
    queryKey: ['talent-masters', talentType],
    queryFn: () => api<TalentMasterChoice[]>(`/api/v1/talent-masters/${talentType}`),
    enabled: talentType !== 'CAREER',
  })

  const mutation = useMutation({
    mutationFn: async ({ action, values }: { action: 'save' | 'submit'; values: FormValues }) => {
      const result = schemas[talentType].safeParse(values)
      if (!result.success) throw new Error(result.error.issues[0]?.message ?? '入力内容を確認してください')
      const payload = result.data as Record<string, unknown>
      if ('endDate' in payload && payload.endDate === '') payload.endDate = null
      if ('expiresOn' in payload && payload.expiresOn === '') payload.expiresOn = null
      if ('credentialReference' in payload && payload.credentialReference === '') payload.credentialReference = null
      let current = draft
      current = current
        ? await api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}`, { method: 'PUT', body: JSON.stringify({ version: current.version, payload }) })
        : await api<TalentSubmission>(`/api/v1/talent-submissions/${talentType}`, { method: 'POST', body: JSON.stringify({ payload }) })
      for (const file of files) {
        const form = new FormData()
        form.append('file', file)
        form.append('version', String(current.version))
        const uploaded: { submissionVersion: number } = await api<{ submissionVersion: number }>(`/api/v1/talent-submissions/${current.publicId}/attachments`, { method: 'POST', body: form })
        current = { ...current, version: uploaded.submissionVersion }
      }
      return action === 'submit'
        ? api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}/submit`, { method: 'POST', body: JSON.stringify({ version: current.version }) })
        : current
    },
    onSuccess: async (value, variables) => {
      setDraft(value); setFiles([])
      setMessage(variables.action === 'submit' ? '直属上長へ申請しました。' : '下書きを保存しました。')
      await client.invalidateQueries({ queryKey: ['talent-submissions'] })
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : '処理できませんでした'),
  })

  const selectFiles = (list: FileList | null) => {
    const selected = Array.from(list ?? [])
    const accepted = new Set(['application/pdf', 'image/jpeg', 'image/png'])
    setMessage(null)
    if (selected.length > 3 || selected.some((file) => file.size > 5_242_880)) {
      setMessage('添付は3件まで、1件5MB以内です。'); return
    }
    if (selected.some((file) => !accepted.has(file.type))) {
      setMessage('添付できる形式はPDF・JPEG・PNGです。'); return
    }
    setFiles(selected)
  }
  const run = (action: 'save' | 'submit') => handleSubmit((values) => mutation.mutate({ action, values }))()

  return <>
    <div className="page-heading"><div><span className="eyebrow">TALENT APPLICATION</span><h1>{labels[talentType]}を申請</h1><p>必要事項を入力し、任意の根拠ファイルを添付して直属上長へ提出します</p></div></div>
    <section className="card talent-form">
      {talentType !== 'CAREER' && <label>{labels[talentType]}<select {...register('masterPublicId')}><option value="">選択してください</option>{masterQuery.data?.map((item) => <option key={item.publicId} value={item.publicId}>{item.code} / {item.name}</option>)}</select></label>}
      {(talentType === 'SKILL' || talentType === 'KNOWLEDGE') && <label>習熟度（1～5）<input type="number" min={1} max={5} {...register('level')} /></label>}
      {talentType === 'SKILL' && <><label>経験年数<input type="number" min={0} max={60} step="0.1" {...register('yearsExperience')} /></label><label>最終利用日<input type="date" {...register('lastUsedOn')} /></label></>}
      {(talentType === 'SKILL' || talentType === 'KNOWLEDGE') && <label>根拠<textarea maxLength={1000} {...register('evidence')} /></label>}
      {talentType === 'CAREER' && <><label>案件名<input maxLength={150} {...register('projectName')} /></label><label>業界<input maxLength={100} {...register('industry')} /></label><label>役割<input maxLength={100} {...register('roleName')} /></label><label>開始日<input type="date" {...register('startDate')} /></label><label>終了日<input type="date" {...register('endDate')} /></label><label>概要<textarea maxLength={1000} {...register('summary')} /></label><label>成果<textarea maxLength={1500} {...register('achievements')} /></label><label>利用技術<textarea maxLength={1000} {...register('technologies')} /></label></>}
      {talentType === 'CERTIFICATION' && <><label>取得日<input type="date" {...register('acquiredOn')} /></label><label>有効期限<input type="date" {...register('expiresOn')} /></label><label>資格番号<input maxLength={100} {...register('credentialReference')} /></label></>}
      <label className="reason-box">根拠ファイル（PDF/JPEG/PNG、3件まで）<input type="file" accept=".pdf,.jpg,.jpeg,.png" multiple onChange={(event) => selectFiles(event.target.files)} /></label>
      {draft && <p className="notice">状態: {draft.status} / 版: {draft.revisionNo}</p>}
      {message && <div role="status" className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
      <div className="form-actions"><Link className="secondary-button" to="/skills/edit">戻る</Link><button className="secondary-button" disabled={mutation.isPending} onClick={() => run('save')}>下書き保存</button><button className="primary-button" disabled={mutation.isPending} onClick={() => run('submit')}>直属上長へ申請</button></div>
    </section>
  </>
}
