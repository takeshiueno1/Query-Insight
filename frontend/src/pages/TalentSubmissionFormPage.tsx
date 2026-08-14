import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { api, ApiError } from '../lib/api'
import { competencyLevels } from '../lib/competency'
import { talentCategoryRoutes, talentSubmissionStatusLabels, talentSubmissionTypeLabels, type TalentMasterChoice, type TalentSubmission, type TalentSubmissionPayload, type TalentSubmissionType } from '../types'

type FormValues = {
  masterPublicId: string; level: string; yearsExperience: string; lastUsedOn: string; evidence: string
  projectName: string; industry: string; roleName: string; startDate: string; endDate: string
  summary: string; achievements: string; technologies: string; acquiredOn: string; expiresOn: string
  credentialReference: string
}

type AttachmentUpload = {
  publicId: string
  fileName: string
  contentType: string
  sizeBytes: number
  scanStatus: 'PENDING' | 'CLEAN' | 'INFECTED' | 'ERROR'
  submissionVersion: number
}

const initialValues: FormValues = {
  masterPublicId: '', level: '3', yearsExperience: '0', lastUsedOn: '', evidence: '',
  projectName: '', industry: '', roleName: '', startDate: '', endDate: '', summary: '', achievements: '',
  technologies: '', acquiredOn: '', expiresOn: '', credentialReference: '',
}

const required = (label: string, max: number) => z.string().trim().min(1, `${label}を入力してください`).max(max)
const schemas = {
  SKILL: z.object({ masterPublicId: required('スキル', 26), level: z.coerce.number().int().min(1).max(5), yearsExperience: z.coerce.number().min(0).max(60), lastUsedOn: required('最終利用日', 10), evidence: required('根拠', 1000) }),
  KNOWLEDGE: z.object({ masterPublicId: required('得意分野', 26), level: z.coerce.number().int().min(1).max(5), evidence: required('根拠', 1000) }),
  CAREER: z.object({ projectName: required('案件名', 150), industry: required('業界', 100), roleName: required('役割', 100), startDate: required('開始日', 10), endDate: z.string(), summary: required('概要', 1000), achievements: required('成果', 1500), technologies: required('利用技術', 1000) }),
  CERTIFICATION: z.object({ masterPublicId: required('資格', 26), acquiredOn: required('取得日', 10), expiresOn: z.string(), credentialReference: z.string().max(100) }),
} satisfies Record<TalentSubmissionType, z.ZodType>

export function TalentSubmissionFormPage() {
  const { type = 'SKILL', publicId } = useParams()
  const talentType = (Object.keys(talentSubmissionTypeLabels).includes(type.toUpperCase()) ? type.toUpperCase() : 'SKILL') as TalentSubmissionType
  const label = talentSubmissionTypeLabels[talentType]
  const { register, handleSubmit, reset } = useForm<FormValues>({ defaultValues: initialValues })
  const [draft, setDraft] = useState<TalentSubmission | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const client = useQueryClient()
  const navigate = useNavigate()
  const masterQuery = useQuery({
    queryKey: ['talent-masters', talentType],
    queryFn: () => api<TalentMasterChoice[]>(`/api/v1/talent-masters/${talentType}`),
    enabled: talentType !== 'CAREER',
  })
  const existingQuery = useQuery({
    queryKey: ['talent-submissions', 'mine', talentType],
    queryFn: () => api<TalentSubmission[]>(`/api/v1/talent-submissions/me?type=${talentType}`),
    enabled: Boolean(publicId),
  })
  const existing = existingQuery.data?.find((item) => item.publicId === publicId)
  const activeSubmission = existing ?? (draft?.publicId === publicId ? draft : undefined)

  useEffect(() => {
    if (!existing || draft !== null) return
    setDraft(existing)
    reset(toFormValues(existing.payload))
  }, [draft, existing, reset])

  const persistSubmission = (submission: TalentSubmission) => {
    setDraft(submission)
    client.setQueryData<TalentSubmission[]>(['talent-submissions', 'mine', talentType], (current = []) => [
      ...current.filter((item) => item.publicId !== submission.publicId),
      submission,
    ])
    if (publicId !== submission.publicId) navigate(`/talent/${talentType}/${submission.publicId}/edit`, { replace: true })
  }

  const recoverSubmission = async (failed: TalentSubmission | null) => {
    if (!failed) return
    try {
      const submissions = await api<TalentSubmission[]>(`/api/v1/talent-submissions/me?type=${talentType}`)
      const recovered = submissions
        .filter((item) => item.logicalPublicId === failed.logicalPublicId)
        .sort((left, right) => right.revisionNo - left.revisionNo || right.version - left.version)[0]
      if (!recovered || recovered.revisionNo < failed.revisionNo
          || recovered.revisionNo === failed.revisionNo && recovered.version < failed.version) return
      persistSubmission(recovered)
    } catch {
      // The original operation error remains the actionable message; recovery is best effort.
    }
  }

  const mutation = useMutation({
    mutationFn: async ({ action, values }: { action: 'save' | 'submit'; values: FormValues }) => {
      const result = schemas[talentType].safeParse(values)
      if (!result.success) throw new Error(result.error.issues[0]?.message ?? '入力内容を確認してください')
      const payload = result.data as TalentSubmissionPayload
      if ('endDate' in payload && payload.endDate === '') payload.endDate = null
      if ('expiresOn' in payload && payload.expiresOn === '') payload.expiresOn = null
      if ('credentialReference' in payload && payload.credentialReference === '') payload.credentialReference = null
      let current = draft
      try {
        current = current
          ? await api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}`, { method: 'PUT', body: JSON.stringify({ version: current.version, payload }) })
          : await api<TalentSubmission>(`/api/v1/talent-submissions/${talentType}`, { method: 'POST', body: JSON.stringify({ payload }) })
      } catch (error) {
        await recoverSubmission(current)
        throw error
      }
      persistSubmission(current)
      let uploadedCount = 0
      for (const file of files) {
        const form = new FormData()
        form.append('file', file)
        form.append('version', String(current.version))
        let uploaded: AttachmentUpload
        try {
          uploaded = await api<AttachmentUpload>(`/api/v1/talent-submissions/${current.publicId}/attachments`, { method: 'POST', body: form })
        } catch (error) {
          await recoverSubmission(current)
          const saved = uploadedCount > 0 ? '成功済みの添付は保存されています。' : '下書きは保存されています。'
          throw new Error(`${saved}再実行すると未完了の添付から続行します。${errorDetail(error)}`)
        }
        current = { ...current, version: uploaded.submissionVersion }
        persistSubmission(current)
        setFiles((pending) => pending.filter((item) => item !== file))
        if (uploaded.scanStatus === 'INFECTED') {
          throw new Error('安全でない添付ファイルが隔離されました。申請は提出されていません。')
        }
        if (uploaded.scanStatus !== 'CLEAN') {
          throw new Error('添付ファイルは保存され、検査完了を待っています。申請は提出されていません。')
        }
        uploadedCount += 1
      }
      if (action === 'submit') {
        try {
          current = await api<TalentSubmission>(`/api/v1/talent-submissions/${current.publicId}/submit`, { method: 'POST', body: JSON.stringify({ version: current.version }) })
        } catch (error) {
          await recoverSubmission(current)
          throw new Error(`下書きは保存されています。${errorDetail(error)}`)
        }
        persistSubmission(current)
      }
      return current
    },
    onSuccess: async (value, variables) => {
      persistSubmission(value); setFiles([])
      await client.invalidateQueries({ queryKey: ['talent-submissions'], refetchType: 'inactive' })
      setMessage(variables.action === 'submit' ? '直属上長へ申請しました。' : '下書きを保存しました。')
    },
    onError: (error) => setMessage(errorDetail(error)),
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

  if (publicId && existingQuery.isLoading) return <section className="card" role="status"><p>申請内容を読み込んでいます…</p></section>
  if (publicId && (existingQuery.isError || existingQuery.data && !activeSubmission)) return <section className="card" role="alert"><p className="error-banner">申請内容を取得できませんでした。</p></section>
  const submittedHere = draft?.publicId === activeSubmission?.publicId && draft?.status === 'SUBMITTED'
  if (publicId && activeSubmission && !['DRAFT', 'RETURNED'].includes(activeSubmission.status) && !submittedHere) return <section className="card" role="alert"><p className="error-banner">{talentSubmissionStatusLabels[activeSubmission.status]}の申請は編集できません。</p><Link className="text-link" to={talentCategoryRoutes[talentType]}>一覧へ戻る</Link></section>
  if (publicId && activeSubmission && !draft) return <section className="card" role="status"><p>申請内容を読み込んでいます…</p></section>

  return <>
    <div className="page-heading"><div><span className="eyebrow">タレント情報</span><h1>{publicId ? `${label}申請を編集` : `${label}を登録`}</h1><p>必要事項を入力し、任意の根拠ファイルを添付して直属上長へ提出します</p></div></div>
    <section className="card talent-form">
      <fieldset className="talent-form-fields" disabled={submittedHere}>
      {talentType !== 'CAREER' && <label>{label}<select {...register('masterPublicId')}><option value="">選択してください</option>{masterQuery.data?.map((item) => <option key={item.publicId} value={item.publicId}>{item.name}</option>)}</select></label>}
      {(talentType === 'SKILL' || talentType === 'KNOWLEDGE') && <label>習熟状況
        <select aria-label="習熟状況" aria-describedby="competency-level-help" {...register('level')}>
          {competencyLevels.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <small id="competency-level-help">{competencyLevels.map((item) => `${item.label}：${item.description}`).join('。')}</small>
      </label>}
      {talentType === 'SKILL' && <><label>経験年数<input type="number" min={0} max={60} step="0.1" {...register('yearsExperience')} /></label><label>最終利用日<input type="date" {...register('lastUsedOn')} /></label></>}
      {(talentType === 'SKILL' || talentType === 'KNOWLEDGE') && <label>根拠<textarea maxLength={1000} {...register('evidence')} /></label>}
      {talentType === 'CAREER' && <><label>案件名<input maxLength={150} {...register('projectName')} /></label><label>業界<input maxLength={100} {...register('industry')} /></label><label>役割<input maxLength={100} {...register('roleName')} /></label><label>開始日<input type="date" {...register('startDate')} /></label><label>終了日<input type="date" {...register('endDate')} /></label><label>概要<textarea maxLength={1000} {...register('summary')} /></label><label>成果<textarea maxLength={1500} {...register('achievements')} /></label><label>利用技術<textarea maxLength={1000} {...register('technologies')} /></label></>}
      {talentType === 'CERTIFICATION' && <><label>取得日<input type="date" {...register('acquiredOn')} /></label><label>有効期限<input type="date" {...register('expiresOn')} /></label><label>資格番号<input maxLength={100} {...register('credentialReference')} /></label></>}
      {publicId && <p className="notice">既存の添付資料一覧は表示できません。差戻し後に必要な資料は再度添付してください。</p>}
      <label className="reason-box">根拠資料（任意）<small>PDF/JPEG/PNG、3件まで、1件5MB以内</small><input aria-label="根拠資料（任意）" type="file" accept=".pdf,.jpg,.jpeg,.png" multiple onChange={(event) => selectFiles(event.target.files)} /></label>
      </fieldset>
      {draft && <p className="notice">状態: {talentSubmissionStatusLabels[draft.status]} / 第{draft.revisionNo}版</p>}
      {message && <div role={mutation.isError ? 'alert' : 'status'} className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
      <div className="form-actions"><Link className="secondary-button" to={talentCategoryRoutes[talentType]}>戻る</Link>{!submittedHere && <><button className="secondary-button" disabled={mutation.isPending} onClick={() => run('save')}>下書き保存</button><button className="primary-button" disabled={mutation.isPending} onClick={() => run('submit')}>直属上長へ申請</button></>}</div>
    </section>
  </>
}

function toFormValues(payload: TalentSubmissionPayload): FormValues {
  return {
    ...initialValues,
    masterPublicId: text(payload.masterPublicId),
    level: text(payload.level, '3'),
    yearsExperience: text(payload.yearsExperience, '0'),
    lastUsedOn: text(payload.lastUsedOn),
    evidence: text(payload.evidence),
    projectName: text(payload.projectName),
    industry: text(payload.industry),
    roleName: text(payload.roleName),
    startDate: text(payload.startDate),
    endDate: text(payload.endDate),
    summary: text(payload.summary),
    achievements: text(payload.achievements),
    technologies: text(payload.technologies),
    acquiredOn: text(payload.acquiredOn),
    expiresOn: text(payload.expiresOn),
    credentialReference: text(payload.credentialReference),
  }
}

function text(value: string | number | null | undefined, fallback = '') {
  return value === null || value === undefined ? fallback : String(value)
}

function errorDetail(error: unknown) {
  return error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : '処理できませんでした'
}
