import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../types'
import { AppLayout } from './AppLayout'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))
vi.mock('../lib/api', () => ({ api: apiMock }))

describe('権限別ナビゲーション', () => {
  beforeEach(() => {
    useAuthMock.mockReset()
    apiMock.mockReset().mockResolvedValue({ unreadCount: 0 })
  })
  afterEach(cleanup)

  it.each([
    ['一般', user('GENERAL', 'SELF'), [], ['社員検索', '上長評価入力', 'タレント承認', '最終承認', '監査']],
    ['直属部下の役職者', user('OFFICER', 'SUBORDINATES'), ['社員検索', '上長評価入力', 'タレント承認'], ['最終承認', '監査']],
    ['全社の役職者', user('OFFICER', 'ALL'), ['社員検索', '最終承認'], ['上長評価入力', 'タレント承認', '監査']],
    ['管理者', user('ADMIN', 'ALL'), ['社員検索', '監査'], ['上長評価入力', 'タレント承認', '最終承認']],
  ])('%sに許可されたメニューだけを表示する', (_label, currentUser, visible, hidden) => {
    renderLayout(currentUser)

    for (const label of visible) expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    for (const label of hidden) expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument()
  })

  it('全利用者の主要メニューを用途別の日本語名で表示する', () => {
    renderLayout(user('GENERAL', 'SELF'))

    for (const label of ['ダッシュボード', 'スキル', '業務経歴', '資格', '上長評価', '通知', 'マスタ申請']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: 'スキル' })).toHaveAttribute('href', '/skills')
    expect(screen.getByRole('link', { name: '業務経歴' })).toHaveAttribute('href', '/careers')
    expect(screen.getByRole('link', { name: '資格' })).toHaveAttribute('href', '/certifications')
    expect(screen.getByRole('link', { name: '上長評価' })).toHaveAttribute('href', '/evaluations/manager-result')
    expect(screen.queryByRole('link', { name: '評価' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'AI分析' })).not.toBeInTheDocument()
  })

  it('役職者のscopeごとに評価routeを分ける', () => {
    const subordinate = renderLayout(user('OFFICER', 'SUBORDINATES'))
    expect(screen.getByRole('link', { name: '上長評価入力' })).toHaveAttribute('href', '/evaluations/manager')
    expect(screen.queryByRole('link', { name: '最終承認' })).not.toBeInTheDocument()

    subordinate.unmount()
    renderLayout(user('OFFICER', 'ALL'))
    expect(screen.getByRole('link', { name: '最終承認' })).toHaveAttribute('href', '/executive/evaluations')
    expect(screen.queryByRole('link', { name: '上長評価入力' })).not.toBeInTheDocument()
  })

  it('正式ロゴと日本語パンくずを表示し内部pathを見せない', () => {
    renderLayout(user('GENERAL', 'SELF'), '/skills/edit')

    expect(screen.getByRole('img', { name: 'QUERY INSIGHT' })).toBeInTheDocument()
    expect(screen.getByText('スキル', { selector: '.breadcrumb' })).toBeInTheDocument()
    expect(screen.queryByText(/SKILLS\/EDIT/)).not.toBeInTheDocument()
  })

  it.each([
    [0, null],
    [1, '1'],
    [99, '99'],
    [100, '99+'],
  ])('未読数が%d件のとき通知バッジを%sと表示する', async (unreadCount, visibleCount) => {
    apiMock.mockResolvedValue({ unreadCount })
    renderLayout(user('GENERAL', 'SELF'))

    if (visibleCount === null) {
      expect(await screen.findByRole('navigation')).toBeInTheDocument()
      expect(document.querySelector('.notification-badge')).not.toBeInTheDocument()
      return
    }
    const badge = await screen.findByLabelText(`未読通知${unreadCount}件`)
    expect(badge).toHaveTextContent(visibleCount)
  })

  it('同じQueryClientで利用者が切り替わっても前利用者の未読数を表示しない', async () => {
    apiMock.mockResolvedValueOnce({ unreadCount: 7 }).mockResolvedValueOnce({ unreadCount: 2 })
    const client = queryClient()
    const { rerender } = renderLayout(user('GENERAL', 'SELF', 'ACCOUNT-A'), '/', client)
    expect(await screen.findByLabelText('未読通知7件')).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: user('GENERAL', 'SELF', 'ACCOUNT-B'), loading: false, logout: vi.fn() })
    rerender(layoutTree(client))

    expect(screen.queryByLabelText('未読通知7件')).not.toBeInTheDocument()
    expect(await screen.findByLabelText('未読通知2件')).toBeInTheDocument()
    expect(apiMock).toHaveBeenCalledTimes(2)
  })

  it('未読数の再取得に失敗したら取得済みの古いバッジを隠す', async () => {
    apiMock.mockResolvedValueOnce({ unreadCount: 4 }).mockRejectedValueOnce(new Error('再取得失敗'))
    const client = queryClient()
    renderLayout(user('GENERAL', 'SELF'), '/', client)
    expect(await screen.findByLabelText('未読通知4件')).toBeInTheDocument()

    await act(() => client.invalidateQueries({ queryKey: ['notifications'] }))

    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(2))
    expect(screen.queryByLabelText('未読通知4件')).not.toBeInTheDocument()
  })
})

function renderLayout(currentUser: User, path = '/', client = queryClient()) {
  useAuthMock.mockReturnValue({ user: currentUser, loading: false, logout: vi.fn() })
  return render(layoutTree(client, path))
}

function layoutTree(client: QueryClient, path = '/') {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}><AppLayout /></MemoryRouter>
    </QueryClientProvider>
  )
}

function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function user(role: User['roles'][number], scope: User['scopes'][number], accountPublicId = 'A'): User {
  return { accountPublicId, employeePublicId: 'E', displayName: '試験 利用者', roles: [role], scopes: [scope] }
}
