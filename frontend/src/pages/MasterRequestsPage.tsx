import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError } from '../lib/api'
import type { MasterRequest } from '../types'
export function MasterRequestsPage() {
  const client = useQueryClient(); const [type, setType] = useState<'SKILL'|'CERTIFICATION'>('SKILL'); const [payload, setPayload] = useState('{}'); const [message, setMessage] = useState<string|null>(null)
  const query = useQuery({ queryKey: ['master-requests'], queryFn: () => api<MasterRequest[]>('/api/v1/master-requests/me') })
  const mutation = useMutation({ mutationFn: () => api<MasterRequest>('/api/v1/master-requests', { method: 'POST', body: JSON.stringify({ type, payload: JSON.parse(payload) as unknown }) }), onSuccess: async () => { setMessage('マスタ追加を申請しました。'); await client.invalidateQueries({ queryKey: ['master-requests'] }) }, onError: (error) => setMessage(error instanceof ApiError ? error.problem.detail : 'JSON形式を確認してください') })
  return <><div className="page-heading"><div><span className="eyebrow">MASTER REQUEST</span><h1>マスタ追加申請</h1><p>選択肢にないスキル・資格を申請できます</p></div></div><div className="decision-grid"><section className="card"><label>種類<select value={type} onChange={(e) => setType(e.target.value as typeof type)}><option value="SKILL">スキル</option><option value="CERTIFICATION">資格</option></select></label><label className="reason-box">申請内容（JSON）<textarea value={payload} onChange={(e) => setPayload(e.target.value)} /></label>{message && <div className={mutation.isError ? 'error-banner' : 'success-banner'}>{message}</div>}<div className="form-actions"><button className="primary-button" onClick={() => mutation.mutate()}>申請する</button></div></section><section className="card"><h2>自分の申請</h2>{query.data?.map((item) => <article key={item.publicId} className="notice"><strong>{item.type} / {item.status}</strong>{item.returnReason && <p>{item.returnReason}</p>}</article>)}</section></div></>
}
