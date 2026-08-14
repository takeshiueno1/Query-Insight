import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('./features/auth/auth-context', () => ({ useAuth: useAuthMock }))
vi.mock('./lib/api', () => ({
  api: apiMock,
  ApiError: class extends Error {
    problem: unknown
    constructor(problem: unknown) { super('API error'); this.problem = problem }
  },
}))

describe('到達可能な画面の利用者向け見出し', () => {
  beforeEach(() => {
    useAuthMock.mockReturnValue({
      user: { accountPublicId: 'ACCOUNT-1', employeePublicId: 'EMPLOYEE-1', displayName: '試験 利用者', roles: ['ADMIN'], scopes: ['ALL'] },
      loading: false,
      logout: vi.fn(),
    })
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/notifications/unread-count') return Promise.resolve({ unreadCount: 0 })
      if (path === '/api/v1/audit-logs') {
        return Promise.resolve([{
          publicId: 'AUDIT-1', occurredAt: '2026-08-14T00:00:00Z', actorPublicId: 'ACCOUNT-1',
          action: 'AUTH_LOGIN', targetType: 'ACCOUNT', targetPublicId: 'ACCOUNT-1',
          result: 'SUCCESS', dataScope: 'SELF', traceId: 'TRACE-1',
        }])
      }
      if (path === '/api/v1/notifications') return Promise.resolve([])
      if (path.startsWith('/api/v1/employees?')) {
        return Promise.resolve({
          content: [{
            publicId: 'EMPLOYEE-1', employeeNo: 'QI0008', name: '試験 社員',
            departmentName: '開発部', positionName: '担当者', employmentStatus: 'ACTIVE',
          }],
          totalElements: 1, totalPages: 1, page: 0, size: 20,
        })
      }
      if (path === '/api/v1/employees/EMPLOYEE-1') {
        return Promise.resolve({
          publicId: 'EMPLOYEE-1', employeeNo: 'QI0008', lastName: '試験', firstName: '社員',
          email: 'test@example.invalid', departmentName: '開発部', positionName: '担当者',
          employmentStatus: 'ACTIVE', hireDate: '2024-04-01', managerName: '試験 上長',
          updatedAt: '2026-08-14T00:00:00Z', version: 0,
        })
      }
      if (path === '/api/v1/employees/EMPLOYEE-1/talent-profile') {
        return Promise.resolve({ skills: [], knowledge: [], careers: [], certifications: [] })
      }
      return Promise.resolve([])
    })
  })
  afterEach(cleanup)

  it.each([
    ['/analysis', 'AI能力分析', '能力分析'],
    ['/audit', '監査ログ', '管理・監査'],
    ['/employees', '社員検索', '社員情報'],
    ['/employees/EMPLOYEE-1', '社員詳細', '社員情報'],
    ['/employees/new', '社員登録', '社員情報'],
    ['/notifications', '通知', 'お知らせ'],
    ['/masters', 'マスタ管理', '管理設定'],
    ['/password/change', 'パスワード変更', 'アカウント'],
    ['/password/reset', 'パスワード再設定', 'アカウント'],
    ['/evaluations/history', '評価履歴比較', '評価'],
    ['/organization', '組織・所属管理', '組織管理'],
    ['/accounts', 'アカウント・権限管理', '管理設定'],
  ])('%sは「%s」で内部画面IDではなく自然な日本語文脈を表示する', async (path, heading, eyebrow) => {
    renderApp(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(document.querySelector('.page-heading .eyebrow')).toHaveTextContent(eyebrow)
    expect(document.body).not.toHaveTextContent(/SCR-[0-9]+/)
  })

  it.each([
    ['/employees', '在籍'],
    ['/employees/EMPLOYEE-1', '在籍'],
  ])('%sは在籍状態の内部コードを日本語で表示する', async (path, label) => {
    renderApp(path)

    expect(await screen.findByText(label)).toBeVisible()
    expect(document.body).not.toHaveTextContent(/\bACTIVE\b/)
  })

  it('監査ログは操作・対象・結果・スコープを日本語で表示する', async () => {
    renderApp('/audit')

    expect(await screen.findByText('ログイン')).toBeVisible()
    expect(screen.getByText('アカウント')).toBeVisible()
    expect(screen.getByText('成功')).toBeVisible()
    expect(screen.getByText('本人')).toBeVisible()
    expect(document.body).not.toHaveTextContent(/\bAUTH_LOGIN\b|\bACCOUNT\b|\bSUCCESS\b|\bSELF\b/)
  })
})

function renderApp(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
    </QueryClientProvider>,
  )
}
