import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../features/auth/auth-context'
import type { AiAnalysis, Dashboard, User } from '../types'
import { DashboardPage } from './DashboardPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))

const user: User = {
  accountPublicId: 'ACCOUNT-OWNER', employeePublicId: 'EMPLOYEE-OWNER', displayName: '山田 太郎',
  roles: ['GENERAL'], scopes: ['SELF'],
}

const dashboard: Dashboard = {
  profile: {
    employeeNo: 'QI0100', name: '山田 太郎', department: '開発部', positionName: 'エンジニア',
    updatedAt: '2026-08-14T01:00:00Z',
  },
  profileStatus: {
    publicId: 'STATUS-OWNER', skillScore: 90, knowledgeScore: 0, careerScore: 80,
    certificationScore: 60, totalScore: 75, grade: 'B', missingCategories: ['KNOWLEDGE'],
    formulaVersion: 'PROFILE_STATUS_V1', calculatedAt: '2026-08-14T02:00:00Z', editable: false,
  },
  finalManagerEvaluation: {
    status: 'FINALIZED', finalRank: 'A', summary: '期待役割を安定して果たしています。',
    finalizedAt: '2026-08-13T03:00:00Z',
    details: [
      { axisCode: 'TECHNICAL', displayName: '技術力', managerRank: 'S', comment: '品質改善を主導しました。' },
      { axisCode: 'DESIGN', displayName: '設計力', managerRank: 'A', comment: '設計判断を共有しました。' },
    ],
  },
  unreadNotifications: 3,
}

const prototypeAnalysis: AiAnalysis = {
  publicId: 'ANALYSIS-1', periodName: '2026年度', summary: '承認済み情報から強みを整理しました。',
  strengths: [{ title: 'スキル', evidence: '高いスキル点を維持しています。' }],
  growthAreas: [{ title: '得意分野', evidence: '未登録のため追加を検討してください。' }],
  recommendedActions: [{ action: '得意分野を登録する', priority: 'HIGH' }],
  model: 'ルールベース V1', generatedAt: '2026-08-14T04:00:00Z', analysisMode: 'PROTOTYPE',
}

function authValue(currentUser: User): AuthContextValue {
  return { user: currentUser, loading: false, login: vi.fn(), logout: vi.fn() }
}

function renderDashboard(currentUser = user, client = new QueryClient({ defaultOptions: { queries: { retry: false, throwOnError: false }, mutations: { retry: false, throwOnError: false } } })) {
  return {
    client,
    ...render(
      <MemoryRouter>
        <AuthContext.Provider value={authValue(currentUser)}>
          <QueryClientProvider client={client}><DashboardPage /></QueryClientProvider>
        </AuthContext.Provider>
      </MemoryRouter>,
    ),
  }
}

describe('DashboardPage', () => {
  beforeEach(() => { apiMock.mockReset() })
  afterEach(cleanup)

  it('編集不能な自己ステータス、能力バランス、公開済み評価、プロトタイプ分析を集約する', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/dashboard/me'
      ? Promise.resolve(dashboard)
      : Promise.resolve(prototypeAnalysis))
    renderDashboard()

    expect(await screen.findByRole('heading', { name: '自己ステータス' })).toBeInTheDocument()
    expect(screen.getByLabelText('自己ステータスのランク B')).toBeInTheDocument()
    expect(screen.getAllByText('75.0点')).not.toHaveLength(0)
    expect(screen.getAllByText('スキル')).not.toHaveLength(0)
    expect(screen.getAllByText('得意分野')).not.toHaveLength(0)
    expect(screen.getAllByText('業務経歴')).not.toHaveLength(0)
    expect(screen.getAllByText('資格')).not.toHaveLength(0)
    expect(screen.getByText('未登録: 得意分野')).toBeInTheDocument()
    expect(screen.getByText(/算出式: スキル40%/)).toBeInTheDocument()
    expect(screen.getByText(/算出日時/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '承認済み情報の能力バランス' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '公開済み上長評価' })).toBeInTheDocument()
    expect(screen.getByLabelText('上長評価の総合ランク A')).toBeInTheDocument()
    expect(screen.getByText('品質改善を主導しました。')).toBeInTheDocument()
    expect(screen.getByLabelText('未読通知 3件')).toBeInTheDocument()
    expect(screen.queryByText('次にやること')).not.toBeInTheDocument()
    expect(screen.queryByText('評価を編集')).not.toBeInTheDocument()
    expect(screen.queryByText('自己評価')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '分析を実行' }))
    expect(await screen.findByText('プロトタイプ分析')).toBeInTheDocument()
    expect(screen.getByText('承認済み情報から強みを整理しました。')).toBeInTheDocument()
    expect(screen.getByText('優先度: 高')).toBeInTheDocument()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/ai-analyses', { method: 'POST' })
  })

  it('公開済み評価と分析結果がない状態を読み上げ可能に表示する', async () => {
    apiMock.mockResolvedValue({ ...dashboard, finalManagerEvaluation: null, unreadNotifications: 0 })
    renderDashboard()

    expect(await screen.findByRole('status', { name: '上長評価の公開状況' }))
      .toHaveTextContent('公開済みの上長評価はありません')
    expect(screen.getByRole('status', { name: '分析結果の状態' }))
      .toHaveTextContent('分析を実行すると、育成のための助言を表示します')
  })

  it('読込中をARIA live regionとして表示する', () => {
    apiMock.mockImplementation(() => new Promise(() => undefined))
    renderDashboard()
    expect(screen.getByRole('status', { name: 'ダッシュボード読込中' })).toHaveAttribute('aria-busy', 'true')
  })

  it('取得失敗をalertとして表示する', async () => {
    apiMock.mockRejectedValue(new Error('network'))
    renderDashboard()
    expect(await screen.findByRole('alert')).toHaveTextContent('ダッシュボードを表示できません')
  })

  it('分析失敗を再実行可能な日本語エラーとして表示する', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/dashboard/me'
      ? Promise.resolve(dashboard)
      : Promise.reject(new Error('provider detail')))
    renderDashboard()
    fireEvent.click(await screen.findByRole('button', { name: '分析を実行' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('分析を完了できませんでした')
    expect(screen.getByRole('button', { name: '分析を再実行' })).toBeEnabled()
    expect(screen.queryByText('provider detail')).not.toBeInTheDocument()
  })

  it('アカウント切替後に前利用者の未読件数をキャッシュ表示しない', async () => {
    const nextUser = { ...user, accountPublicId: 'ACCOUNT-NEXT', employeePublicId: 'EMPLOYEE-NEXT', displayName: '佐藤 花子' }
    apiMock.mockResolvedValueOnce(dashboard).mockResolvedValueOnce({
      ...dashboard,
      profile: { ...dashboard.profile, employeeNo: 'QI0101', name: '佐藤 花子' },
      unreadNotifications: 0,
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const first = renderDashboard(user, client)
    expect(await screen.findByLabelText('未読通知 3件')).toBeInTheDocument()

    first.rerender(
      <MemoryRouter>
        <AuthContext.Provider value={authValue(nextUser)}>
          <QueryClientProvider client={client}><DashboardPage /></QueryClientProvider>
        </AuthContext.Provider>
      </MemoryRouter>,
    )

    expect(await screen.findByLabelText('未読通知 0件')).toBeInTheDocument()
    expect(screen.getByText(/佐藤 花子.*最新状況/)).toBeInTheDocument()
    expect(apiMock).toHaveBeenCalledTimes(2)
  })
})
