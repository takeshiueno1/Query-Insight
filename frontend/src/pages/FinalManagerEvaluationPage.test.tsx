import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../types'
import App from '../App'
import { ApiError } from '../lib/api'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, api: apiMock }
})

const generalUser: User = {
  accountPublicId: 'ACCOUNT-1', employeePublicId: 'EMPLOYEE-1', displayName: '山田 太郎',
  roles: ['GENERAL'], scopes: ['SELF'],
}
const finalized = {
  status: 'FINALIZED', finalRank: 'B', summary: '具体的な成果に基づく総評です。',
  finalizedAt: '2026-08-14T02:00:00Z',
  details: [
    { axisCode: 'TECHNICAL', displayName: '技術力', managerRank: 'S', comment: '品質改善を主導しました。' },
    { axisCode: 'DESIGN', displayName: '設計力', managerRank: 'A', comment: '設計判断を共有しました。' },
  ],
}

describe('本人向け確定上長評価', () => {
  beforeEach(() => {
    apiMock.mockReset()
    useAuthMock.mockReturnValue({ user: generalUser, loading: false, logout: vi.fn() })
  })
  afterEach(cleanup)

  it('一般本人のrouteで確定rankと公開コメントだけを表示する', async () => {
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.resolve(finalized))
    renderApp('/evaluations/manager-result')

    expect(await screen.findByRole('heading', { name: '上長評価' })).toBeInTheDocument()
    expect(screen.getByLabelText('上長評価の総合ランク B')).toBeInTheDocument()
    expect(screen.getByLabelText('技術力の評価ランク S')).toBeInTheDocument()
    expect(screen.getByText('品質改善を主導しました。')).toBeInTheDocument()
    expect(screen.getByText('具体的な成果に基づく総評です。')).toBeInTheDocument()
    expect(screen.queryByText(/score|level|スコア|点数/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /保存|提出|差し戻し|承認|再オープン/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('旧自己評価routeも本人向け確定結果へ転送する', async () => {
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.resolve(finalized))
    renderApp('/evaluations/self')

    expect(await screen.findByLabelText('上長評価の総合ランク B')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下書き保存' })).not.toBeInTheDocument()
  })

  it('未確定の404は公開結果なしとして読み上げ可能に表示する', async () => {
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.reject(new ApiError({ title: '未確定', detail: '確定した評価結果が見つかりません', status: 404, code: 'NOT_FOUND' })))
    renderApp('/evaluations/manager-result')

    expect(await screen.findByRole('status', { name: '上長評価の公開状況' }))
      .toHaveTextContent('公開済みの上長評価はありません')
    expect(screen.queryByText('確定した評価結果が見つかりません')).not.toBeInTheDocument()
  })

  it('読込中と予期しない取得失敗を異なるARIA状態で表示する', async () => {
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : new Promise(() => undefined))
    const first = renderApp('/evaluations/manager-result')
    expect(screen.getByRole('status', { name: '上長評価を読込中' })).toHaveAttribute('aria-busy', 'true')

    first.unmount()
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.reject(new Error('internal detail')))
    renderApp('/evaluations/manager-result')
    expect(await screen.findByRole('alert')).toHaveTextContent('上長評価を表示できませんでした')
    expect(screen.queryByText('internal detail')).not.toBeInTheDocument()
  })

  it('同じQueryClientで本人が切り替わっても前利用者の確定評価を表示しない', async () => {
    const nextUser = { ...generalUser, accountPublicId: 'ACCOUNT-2', employeePublicId: 'EMPLOYEE-2', displayName: '佐藤 花子' }
    const nextResult = { ...finalized, finalRank: 'A' as const, summary: '次の本人だけに公開する総評です。' }
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.resolve(finalized))
    const client = queryClient()
    const first = renderApp('/evaluations/manager-result', client)
    expect(await screen.findByLabelText('上長評価の総合ランク B')).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: nextUser, loading: false, logout: vi.fn() })
    apiMock.mockImplementation((path: string) => path.endsWith('/unread-count')
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.resolve(nextResult))
    first.rerender(appTree('/evaluations/manager-result', client))

    expect(screen.queryByLabelText('上長評価の総合ランク B')).not.toBeInTheDocument()
    expect(await screen.findByLabelText('上長評価の総合ランク A')).toBeInTheDocument()
  })
})

function renderApp(path: string, client = queryClient()) {
  return render(appTree(path, client))
}

function appTree(path: string, client: QueryClient) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
    </QueryClientProvider>
  )
}

function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}
