import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { TalentProfile, User } from '../types'

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

    expect(screen.getByText('タレント情報を読み込んでいます…')).toBeInTheDocument()
  })

  it('プロフィールを取得できない場合は日本語エラーを表示する', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/notifications/unread-count'
      ? Promise.resolve({ unreadCount: 0 })
      : Promise.reject(new Error('取得失敗')))
    renderAt('/careers')

    expect(await screen.findByText('業務経歴を取得できませんでした。', undefined, asyncWait)).toBeInTheDocument()
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
