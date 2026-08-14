import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../lib/api'
import { useNavigate } from 'react-router-dom'

type Notification = { publicId: string; type: string; title: string; body: string; linkPath: string | null; readAt: string | null; createdAt: string }

export function NotificationsPage() {
  const client = useQueryClient()
  const navigate = useNavigate()
  const [message, setMessage] = useState<string | null>(null)
  const query = useQuery({ queryKey: ['notifications'], queryFn: () => api<Notification[]>('/api/v1/notifications') })
  const all = useMutation({ mutationFn: () => api<void>('/api/v1/notifications/read-all', { method: 'PATCH' }), onSuccess: () => client.invalidateQueries({ queryKey: ['notifications'] }) })
  const read = useMutation({ mutationFn: async (item: Notification) => { if (!item.readAt) await api<void>(`/api/v1/notifications/${item.publicId}/read`, { method: 'PATCH' }); return item }, onSuccess: async (item) => { await client.invalidateQueries({ queryKey: ['notifications'] }); await client.invalidateQueries({ queryKey: ['dashboard'] }); if (item.linkPath) navigate(item.linkPath) }, onError: () => setMessage('通知を既読にできませんでした。もう一度お試しください。') })
  return <><div className="page-heading"><div><span className="eyebrow">お知らせ</span><h1>通知</h1><p>評価期限、差し戻し、更新依頼を確認します</p></div><button className="secondary-button" onClick={() => all.mutate()}>すべて既読</button></div>
    {message && <div className="error-banner">{message}</div>}<section className="card notification-list">{query.isLoading ? <p className="empty">読み込み中…</p> : query.data?.length === 0 ? <p className="empty">通知はありません。</p> : query.data?.map((item) => <article key={item.publicId} className={item.readAt ? 'read' : ''}><span className="notification-icon">{item.readAt ? '✓' : '●'}</span><div><h2>{item.linkPath ? <a className="notification-link" href={item.linkPath} onClick={(event) => { event.preventDefault(); read.mutate(item) }}>{item.title}</a> : item.title}</h2><p>{item.body}</p><time>{new Date(item.createdAt).toLocaleString('ja-JP')}</time></div></article>)}</section></>
}
