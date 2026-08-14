import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
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
    expect(screen.queryByRole('link', { name: '評価' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'AI分析' })).not.toBeInTheDocument()
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
})

function renderLayout(currentUser: User, path = '/') {
  useAuthMock.mockReturnValue({ user: currentUser, loading: false, logout: vi.fn() })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}><AppLayout /></MemoryRouter>
    </QueryClientProvider>,
  )
}

function user(role: User['roles'][number], scope: User['scopes'][number]): User {
  return { accountPublicId: 'A', employeePublicId: 'E', displayName: '試験 利用者', roles: [role], scopes: [scope] }
}
