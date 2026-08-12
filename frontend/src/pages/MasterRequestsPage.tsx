import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { useAuth } from '../features/auth/auth-context'
import { api, ApiError } from '../lib/api'
import type { MasterRequest } from '../types'

type MasterType = 'SKILL' | 'CERTIFICATION'
type RequestValues = { code: string; name: string; category: string; description: string; issuer: string }
const skillSchema = z.object({ code: z.string().trim().regex(/^[A-Za-z0-9_]{1,40}$/), name: z.string().trim().min(1).max(100), category: z.string().trim().min(1).max(40), description: z.string().trim().min(1).max(500) })
const certificationSchema = z.object({ code: z.string().trim().regex(/^[A-Za-z0-9_]{1,50}$/), name: z.string().trim().min(1).max(150), issuer: z.string().trim().min(1).max(150) })

export function MasterRequestsPage() {
  const { user } = useAuth()
  const client = useQueryClient()
  const [type, setType] = useState<MasterType>('SKILL')
  const [message, setMessage] = useState<string | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const { register, handleSubmit, reset } = useForm<RequestValues>()
  const reviewer = user?.roles.some((role) => role === 'EXECUTIVE' || role === 'SYSTEM_ADMIN') ?? false
  const query = useQuery({ queryKey: ['master-requests'], queryFn: () => api<MasterRequest[]>('/api/v1/master-requests/me') })
  const reviewQuery = useQuery({ queryKey: ['admin-master-requests'], queryFn: () => api<MasterRequest[]>('/api/v1/admin/master-requests?status=SUBMITTED'), enabled: reviewer })
  const mutation = useMutation({
    mutationFn: (values: RequestValues) => {
      const parsed = (type === 'SKILL' ? skillSchema : certificationSchema).safeParse(values)
      if (!parsed.success) throw new Error('必須項目と文字数、コード形式を確認してください')
      return api<MasterRequest>('/api/v1/master-requests', { method: 'POST', body: JSON.stringify({ type, payload: parsed.data }) })
    },
    onSuccess: async () => { reset(); setMessage('マスタ追加を申請しました。'); await client.invalidateQueries({ queryKey: ['master-requests'] }) },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : '処理できませんでした'),
  })
  const decision = useMutation({
    mutationFn: ({ item, action }: { item: MasterRequest; action: 'approve' | 'return' }) => api<MasterRequest>(`/api/v1/admin/master-requests/${item.publicId}/${action}`, { method: 'POST', body: JSON.stringify(action === 'approve' ? { version: item.version } : { version: item.version, reason: reasons[item.publicId] }) }),
    onSuccess: async () => { setMessage('マスタ申請を処理しました。'); await client.invalidateQueries({ queryKey: ['admin-master-requests'] }) },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理できませんでした'),
  })
  return <>
    <div className="page-heading"><div><span className="eyebrow">MASTER REQUEST</span><h1>マスタ追加申請</h1><p>選択肢にないスキル・資格を申請できます</p></div></div>
    <div className="decision-grid">
      <section className="card talent-form">
        <label>種類<select value={type} onChange={(event) => { setType(event.target.value as MasterType); reset() }}><option value="SKILL">スキル</option><option value="CERTIFICATION">資格</option></select></label>
        <label>コード<input maxLength={type === 'SKILL' ? 40 : 50} placeholder="例: CLOUD_SECURITY" {...register('code')} /></label>
        <label>名称<input maxLength={type === 'SKILL' ? 100 : 150} {...register('name')} /></label>
        {type === 'SKILL' ? <><label>カテゴリ<input maxLength={40} {...register('category')} /></label><label>説明<textarea maxLength={500} {...register('description')} /></label></> : <label>発行団体<input maxLength={150} {...register('issuer')} /></label>}
        {message && <div role="status" className={mutation.isError || decision.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
        <div className="form-actions"><button className="primary-button" disabled={mutation.isPending} onClick={handleSubmit((values) => mutation.mutate(values))}>申請する</button></div>
      </section>
      <section className="card"><h2>自分の申請</h2>{query.data?.map((item) => <article key={item.publicId} className="notice"><strong>{item.type} / {item.status}</strong><p>{String(item.payload.name ?? '')}</p>{item.returnReason && <p>{item.returnReason}</p>}</article>)}</section>
      {reviewer && <section className="card workflow-history"><h2>承認待ち</h2>{reviewQuery.data?.map((item) => <article key={item.publicId}><strong>{item.type} / {String(item.payload.name ?? '')}</strong><pre>{JSON.stringify(item.payload, null, 2)}</pre><label>差戻し理由<input value={reasons[item.publicId] ?? ''} onChange={(event) => setReasons((old) => ({ ...old, [item.publicId]: event.target.value }))} /></label><div className="form-actions"><button className="secondary-button" disabled={!reasons[item.publicId]?.trim()} onClick={() => decision.mutate({ item, action: 'return' })}>差し戻す</button><button className="primary-button" onClick={() => decision.mutate({ item, action: 'approve' })}>承認する</button></div></article>)}</section>}
    </div>
  </>
}
