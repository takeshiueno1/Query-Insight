import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { TalentProfile, TalentSubmission, User } from '../types'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))
const asyncWait = { timeout: 5_000 }

const currentUser: User = {
  accountPublicId: 'ACCOUNT-1',
  employeePublicId: 'EMPLOYEE-1',
  displayName: '試験 利用者',
  roles: ['GENERAL'],
  scopes: ['SELF'],
}

const profile: TalentProfile = {
  skills: [{ code: 'SK001', name: 'Java', category: '開発', level: 4, yearsExperience: 5, lastUsedOn: '2026-08-01', evidence: '業務実績' }],
  knowledge: [{ code: 'KN001', name: 'ドメイン設計', category: '設計', level: 3, evidence: '設計実績' }],
  careers: [{ projectName: '基幹刷新', industry: '製造', roleName: '担当者', startDate: '2025-04-01', endDate: null, summary: '刷新を担当', achievements: '予定どおり完了', technologies: 'PostgreSQL' }],
  certifications: [{ code: 'CE001', name: '基本情報技術者', issuer: 'IPA', acquiredOn: '2024-04-01', expiresOn: null, verificationStatus: 'VERIFIED' }],
}

describe('タレント情報の種類別ルート', () => {
  beforeEach(() => {
    useAuthMock.mockReset().mockReturnValue({ user: currentUser, loading: false, login: vi.fn(), logout: vi.fn() })
    apiMock.mockReset().mockImplementation((path: string) => {
      if (path === '/api/v1/notifications/unread-count') return Promise.resolve({ unreadCount: 0 })
      if (path === '/api/v1/employees/EMPLOYEE-1/talent-profile') return Promise.resolve(profile)
      return Promise.resolve([])
    })
  })
  afterEach(cleanup)

  it('スキル画面はスキルと得意分野だけを種類別登録先とともに表示する', async () => {
    renderAt('/skills')

    expect(await screen.findByRole('heading', { name: 'スキル' }, asyncWait)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '得意分野' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'スキルを登録' })).toHaveAttribute('href', '/talent/new/SKILL')
    expect(screen.getByRole('link', { name: '得意分野を登録' })).toHaveAttribute('href', '/talent/new/KNOWLEDGE')
    expect(screen.getByText('Java')).toBeInTheDocument()
    expect(screen.getByText('ドメイン設計')).toBeInTheDocument()
    expect(screen.getByLabelText('習熟状況: 高度')).toBeInTheDocument()
    expect(screen.getByLabelText('習熟状況: 自立')).toBeInTheDocument()
    expect(screen.queryByLabelText(/レベル[1-5]/)).not.toBeInTheDocument()
    expect(screen.queryByText('基幹刷新')).not.toBeInTheDocument()
    expect(screen.queryByText('基本情報技術者')).not.toBeInTheDocument()
    expect(screen.queryByText('専門知識')).not.toBeInTheDocument()
  })

  it('業務経歴画面は業務経歴だけを表示する', async () => {
    renderAt('/careers')

    expect(await screen.findByRole('heading', { name: '業務経歴' }, asyncWait)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '業務経歴を登録' })).toHaveAttribute('href', '/talent/new/CAREER')
    expect(screen.getByText('基幹刷新')).toBeInTheDocument()
    expect(screen.queryByText('Java')).not.toBeInTheDocument()
    expect(screen.queryByText('ドメイン設計')).not.toBeInTheDocument()
    expect(screen.queryByText('基本情報技術者')).not.toBeInTheDocument()
  })

  it('資格画面は資格だけを表示し確認状態を日本語化する', async () => {
    renderAt('/certifications')

    expect(await screen.findByRole('heading', { name: '資格' }, asyncWait)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '資格を登録' })).toHaveAttribute('href', '/talent/new/CERTIFICATION')
    expect(screen.getByText('基本情報技術者')).toBeInTheDocument()
    expect(screen.getByText('確認済み')).toBeInTheDocument()
    expect(screen.queryByText('VERIFIED')).not.toBeInTheDocument()
    expect(screen.queryByText('Java')).not.toBeInTheDocument()
    expect(screen.queryByText('基幹刷新')).not.toBeInTheDocument()
  })

  it('承認済みプロフィールと手続き中・差戻し申請を分けて再開導線を表示する', async () => {
    const draft = submission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1)
    const submitted = submission('SUBMITTED', 'SUBMITTED-1', 'CHAIN-2', 1)
    const returned = { ...submission('RETURNED', 'RETURNED-1', 'CHAIN-3', 2), returnReason: '根拠を追記してください' }
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/notifications/unread-count') return Promise.resolve({ unreadCount: 0 })
      if (path === '/api/v1/employees/EMPLOYEE-1/talent-profile') return Promise.resolve(profile)
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([draft, submitted, returned])
      if (path === '/api/v1/talent-submissions/me?type=KNOWLEDGE') return Promise.resolve([])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      return Promise.resolve([])
    })
    renderAt('/skills')

    expect(await screen.findByText('Java', undefined, asyncWait)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '手続き中' })).toBeInTheDocument()
    expect(screen.getByText('下書き')).toBeInTheDocument()
    expect(screen.getByText('申請中')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '差戻し' })).toBeInTheDocument()
    expect(screen.getByText(/差戻し理由:/)).toHaveTextContent('根拠を追記してください')
    expect(screen.getByRole('link', { name: '編集を再開' })).toHaveAttribute('href', '/talent/SKILL/DRAFT-1/edit')
    expect(screen.getByRole('link', { name: '修正して再申請' })).toHaveAttribute('href', '/talent/SKILL/RETURNED-1/edit')
    expect(screen.getAllByRole('link', { name: '履歴を見る' })[0]).toHaveAttribute('href', '/talent/CHAIN-1/history')
    expect(screen.queryByText(/\bDRAFT\b|\bSUBMITTED\b|\bRETURNED\b/)).not.toBeInTheDocument()
  })

  it('既存下書きの再開リンクから編集routeへ移動して同じ申請を読み込む', async () => {
    const draft = submission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1)
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/notifications/unread-count') return Promise.resolve({ unreadCount: 0 })
      if (path === '/api/v1/employees/EMPLOYEE-1/talent-profile') return Promise.resolve(profile)
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([draft])
      if (path === '/api/v1/talent-submissions/me?type=KNOWLEDGE') return Promise.resolve([])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      return Promise.resolve([])
    })
    renderAt('/skills')

    fireEvent.click(await screen.findByRole('link', { name: '編集を再開' }, asyncWait))

    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe('/talent/SKILL/DRAFT-1/edit'), asyncWait)
    expect(await screen.findByDisplayValue('既存の根拠', undefined, asyncWait)).toBeInTheDocument()
  })

  it.each([
    ['/skills/edit', '/skills', 'スキル'],
    ['/careers/edit', '/careers', '業務経歴'],
    ['/certifications/edit', '/certifications', '資格'],
  ])('%sを%sへ置き換えて後方互換を保つ', async (legacyPath, currentPath, heading) => {
    renderAt(legacyPath)

    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe(currentPath), asyncWait)
    expect(await screen.findByRole('heading', { name: heading }, asyncWait)).toBeInTheDocument()
  })

  it('未認証では種類別ルートを表示せずログインへ移動する', async () => {
    useAuthMock.mockReturnValue({ user: null, loading: false, login: vi.fn(), logout: vi.fn() })
    renderAt('/skills')

    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe('/login'), asyncWait)
    expect(screen.queryByText('Java')).not.toBeInTheDocument()
  })

  it('プロフィールの読込中を日本語で表示する', () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/notifications/unread-count'
      ? Promise.resolve({ unreadCount: 0 })
      : new Promise(() => undefined))
    renderAt('/skills')

    expect(screen.getByText('タレント情報を読み込んでいます…').closest('[role="status"]')).toBeInTheDocument()
  })

  it('プロフィールを取得できない場合は日本語エラーを表示する', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/notifications/unread-count') return Promise.resolve({ unreadCount: 0 })
      if (path.startsWith('/api/v1/talent-submissions/me?type=')) return Promise.resolve([])
      return Promise.reject(new Error('取得失敗'))
    })
    renderAt('/careers')

    expect(await screen.findByRole('alert', undefined, asyncWait)).toHaveTextContent('業務経歴を取得できませんでした。')
  })

  it('未登録の区分ごとに空状態を表示する', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/notifications/unread-count'
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.resolve({ skills: [], knowledge: [], careers: [], certifications: [] }))
    renderAt('/skills')

    expect(await screen.findByText('登録済みのスキルはありません。', undefined, asyncWait)).toBeInTheDocument()
    expect(screen.getByText('登録済みの得意分野はありません。')).toBeInTheDocument()
  })

  it('利用可能な分析画面でもKNOWLEDGEを得意分野と表示する', () => {
    renderAt('/analysis')

    expect(screen.getAllByText(/得意分野/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/専門知識/)).not.toBeInTheDocument()
  })
})

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="current-path">{location.pathname}</output>
}

function submission(status: TalentSubmission['status'], publicId: string, logicalPublicId: string, revisionNo: number): TalentSubmission {
  return {
    publicId,
    logicalPublicId,
    type: 'SKILL',
    revisionNo,
    status,
    version: 4,
    returnReason: null,
    payload: { masterPublicId: 'MASTER-1', level: 4, yearsExperience: 5, lastUsedOn: '2026-08-01', evidence: '既存の根拠' },
    submittedAt: status === 'DRAFT' ? null : '2026-08-14T00:00:00Z',
    decidedAt: status === 'RETURNED' ? '2026-08-14T01:00:00Z' : null,
  }
}
