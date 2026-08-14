import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { useAuth } from '../features/auth/auth-context'
import { api, ApiError } from '../lib/api'
import type { MasterRequest } from '../types'

type RequestValues = { type: string; description: string }
const requestSchema = z.object({
  type: z.string().trim().min(1, '種類を入力してください').max(100, '種類は100文字以内で入力してください'),
  description: z.string().trim().min(1, '説明を入力してください').max(1000, '説明は1000文字以内で入力してください'),
})

const statusLabels: Record<MasterRequest['status'], string> = {
  SUBMITTED: '申請中',
  APPROVED: '承認済み',
  RETURNED: '差戻し',
}

function requestedAtLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ja-JP', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

function RequestSummary({ item }: { item: MasterRequest }) {
  return <>
    <strong>{item.type}</strong>
    <p>{item.description}</p>
    <p>状態: {statusLabels[item.status]}</p>
    <p>申請者: {item.requesterName}</p>
    <p>申請日時: {requestedAtLabel(item.requestedAt)}</p>
    {item.returnReason && <p>差戻し理由: {item.returnReason}</p>}
  </>
}

export function MasterRequestsPage() {
  const { user } = useAuth()
  const client = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const { register, handleSubmit, reset } = useForm<RequestValues>()
  const reviewer = user?.roles.includes('ADMIN') ?? false
  const query = useQuery({ queryKey: ['master-requests'], queryFn: () => api<MasterRequest[]>('/api/v1/master-requests/me') })
  const reviewQuery = useQuery({ queryKey: ['admin-master-requests'], queryFn: () => api<MasterRequest[]>('/api/v1/admin/master-requests?status=SUBMITTED'), enabled: reviewer })
  const mutation = useMutation({
    mutationFn: (values: RequestValues) => {
      const parsed = requestSchema.safeParse(values)
      if (!parsed.success) throw new Error(parsed.error.issues[0]?.message ?? '入力内容を確認してください')
      return api<MasterRequest>('/api/v1/master-requests', { method: 'POST', body: JSON.stringify(parsed.data) })
    },
    onSuccess: async () => {
      reset()
      setMessage('マスタ追加を申請しました。')
      await client.invalidateQueries({ queryKey: ['master-requests'] })
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : '処理できませんでした'),
  })
  const decision = useMutation({
    mutationFn: ({ item, action }: { item: MasterRequest; action: 'approve' | 'return' }) => api<MasterRequest>(`/api/v1/admin/master-requests/${item.publicId}/${action}`, { method: 'POST', body: JSON.stringify(action === 'approve' ? { version: item.version } : { version: item.version, reason: reasons[item.publicId] }) }),
    onSuccess: async () => {
      setMessage('マスタ申請を処理しました。')
      await Promise.all([
        client.invalidateQueries({ queryKey: ['admin-master-requests'] }),
        client.invalidateQueries({ queryKey: ['master-requests'] }),
      ])
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : '処理できませんでした'),
  })

  return <>
    <div className="page-heading"><div><h1>マスタ追加申請</h1><p>選択肢にないマスタ候補を提案できます</p></div></div>
    <div className="decision-grid">
      <section className="card talent-form">
        <label>種類<input maxLength={100} {...register('type')} /></label>
        <label>説明<textarea maxLength={1000} {...register('description')} /></label>
        {message && <div role="status" className={mutation.isError || decision.isError ? 'error-banner' : 'success-banner'}>{message}</div>}
        <div className="form-actions"><button className="primary-button" disabled={mutation.isPending} onClick={handleSubmit((values) => mutation.mutate(values))}>申請する</button></div>
      </section>
      <section className="card"><h2>自分の申請</h2>{query.data?.map((item) => <article key={item.publicId} className="notice"><RequestSummary item={item} /></article>)}</section>
      {reviewer && <section className="card workflow-history"><h2>承認待ち</h2>{reviewQuery.data?.map((item) => <article key={item.publicId}><RequestSummary item={item} /><label>差戻し理由<input value={reasons[item.publicId] ?? ''} onChange={(event) => setReasons((old) => ({ ...old, [item.publicId]: event.target.value }))} /></label><div className="form-actions"><button className="secondary-button" disabled={!reasons[item.publicId]?.trim()} onClick={() => decision.mutate({ item, action: 'return' })}>差し戻す</button><button className="primary-button" onClick={() => decision.mutate({ item, action: 'approve' })}>承認する</button></div></article>)}</section>}
    </div>
  </>
}
