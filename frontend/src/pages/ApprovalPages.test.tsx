import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../lib/api'
import type { User } from '../types'
import { ExecutiveDashboardPage } from './ExecutiveDashboardPage'
import { ExecutiveEvaluationDetailPage } from './ExecutiveEvaluationDetailPage'
import { ManagerEvaluationDetailPage } from './ManagerEvaluationDetailPage'
import { ManagerEvaluationsPage } from './ManagerEvaluationsPage'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, api: apiMock }
})

const axes = ['技術力', '設計力', '業務理解', '説明力', '推進力', '改善力']
const officer: User = { accountPublicId: 'ACCOUNT-1', employeePublicId: 'EMPLOYEE-1', displayName: '評価者 一郎', roles: ['OFFICER'], scopes: ['SUBORDINATES'] }
const managerEvaluation = {
  publicId: 'T1', employeePublicId: 'E1', employeeName: '山田 太郎', departmentName: '開発',
  periodName: '2026年度', status: 'MANAGER_RETURNED', targetVersion: 4, late: true,
  summary: '期間中の具体的な成果を確認しました。', grade: 'A',
  details: axes.map((displayName, index) => ({
    axisCode: `AXIS_${index}`, displayName, description: `${displayName}の説明`,
    managerRank: (['S', 'A', 'B', 'C', 'D', 'F'] as const)[index],
    managerComment: `${displayName}の具体的な事実`,
  })),
}

const draftManagerEvaluation = {
  ...managerEvaluation,
  status: 'DRAFT',
  grade: null,
}

const executiveEvaluation = {
  ...managerEvaluation,
  status: 'EXECUTIVE_REVIEW',
  finalGrade: null,
  events: [{
    action: 'MANAGER_SUBMIT', fromStatus: 'MANAGER_IN_PROGRESS', toStatus: 'EXECUTIVE_REVIEW',
    reason: null, comment: null, late: false, deadlineType: 'MANAGER' as const,
    occurredAt: '2026-08-14T01:00:00Z',
  }],
}

describe('評価承認画面', () => {
  beforeEach(() => {
    apiMock.mockReset()
    useAuthMock.mockReturnValue({ user: officer, loading: false, logout: vi.fn() })
  })
  afterEach(cleanup)

  it('上長には担当者、期限超過、日本語状態を表示する', async () => {
    apiMock.mockResolvedValue([{ publicId: 'T1', employeeName: '山田 太郎', departmentName: '開発', periodName: '2026年度', status: 'SELF_SUBMITTED', version: 0, late: true }])
    renderPage(<ManagerEvaluationsPage />)

    expect(await screen.findByText('山田 太郎')).toBeInTheDocument()
    expect(screen.getByText('期限超過', { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByText('上長評価待ち', { selector: 'span' })).toBeInTheDocument()
  })

  it('上長一覧は取得失敗と空一覧を別の状態として表示する', async () => {
    apiMock.mockRejectedValueOnce(new Error('network'))
    const first = renderPage(<ManagerEvaluationsPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('担当評価を取得できませんでした')

    first.unmount()
    apiMock.mockResolvedValueOnce([])
    renderPage(<ManagerEvaluationsPage />)
    expect(await screen.findByRole('status', { name: '担当評価の状態' }))
      .toHaveTextContent('担当する評価はありません')
  })

  it('上長一覧の状態・期限フィルターを維持する', async () => {
    apiMock.mockResolvedValue([
      { publicId: 'T1', employeeName: '期限内の社員', departmentName: '開発', periodName: '2026年度', status: 'SELF_SUBMITTED', version: 0, late: false },
      { publicId: 'T2', employeeName: '期限超過の社員', departmentName: '開発', periodName: '2026年度', status: 'MANAGER_RETURNED', version: 2, late: true },
    ])
    renderPage(<ManagerEvaluationsPage />)
    expect(await screen.findByText('期限内の社員')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('状態'), { target: { value: 'MANAGER_RETURNED' } })
    fireEvent.change(screen.getByLabelText('期限'), { target: { value: 'LATE' } })
    expect(screen.queryByText('期限内の社員')).not.toBeInTheDocument()
    expect(screen.getByText('期限超過の社員')).toBeInTheDocument()
  })

  it('同じQueryClientで評価者が切り替わっても前利用者の部下一覧を表示しない', async () => {
    apiMock.mockResolvedValueOnce([{ publicId: 'T1', employeeName: '前利用者の部下', departmentName: '開発', periodName: '2026年度', status: 'SELF_SUBMITTED', version: 0, late: false }])
      .mockResolvedValue([{ publicId: 'T2', employeeName: '次利用者の部下', departmentName: '営業', periodName: '2026年度', status: 'SELF_SUBMITTED', version: 0, late: false }])
    const client = queryClient()
    const first = renderPage(<ManagerEvaluationsPage />, client)
    expect(await screen.findByText('前利用者の部下')).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: { ...officer, accountPublicId: 'ACCOUNT-2', employeePublicId: 'EMPLOYEE-2' }, loading: false, logout: vi.fn() })
    first.rerender(pageTree(<ManagerEvaluationsPage />, client))

    expect(screen.queryByText('前利用者の部下')).not.toBeInTheDocument()
    expect(await screen.findByText('次利用者の部下')).toBeInTheDocument()
  })

  it('評価者切替時に前利用者の部下詳細をキャッシュ表示しない', async () => {
    apiMock.mockResolvedValueOnce({ ...managerEvaluation, employeeName: '前利用者の部下' })
      .mockResolvedValue({ ...managerEvaluation, employeeName: '次利用者の部下' })
    const client = queryClient()
    const first = renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1', client)
    expect(await screen.findByRole('heading', { name: '前利用者の部下さんの上長評価' })).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: { ...officer, accountPublicId: 'ACCOUNT-2', employeePublicId: 'EMPLOYEE-2' }, loading: false, logout: vi.fn() })
    first.rerender(routeTree(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1', client))

    expect(screen.queryByRole('heading', { name: '前利用者の部下さんの上長評価' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '次利用者の部下さんの上長評価' })).toBeInTheDocument()
  })

  it('DRAFTの上長は6軸・36個のrank選択肢を編集して保存できる', async () => {
    apiMock.mockResolvedValue(draftManagerEvaluation)
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')

    expect(await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(36)
    for (const rank of ['S', 'A', 'B', 'C', 'D', 'F']) {
      expect(screen.getAllByRole('radio', { name: rank })).toHaveLength(6)
    }
    expect(screen.getByRole('heading', { name: '評価基準と注意事項' })).toBeInTheDocument()
    expect(screen.getByText(/属性や印象だけで判断しない/)).toBeInTheDocument()
    expect(screen.getByText(/AIは助言のみ/)).toBeInTheDocument()
    expect(screen.queryByText(/習熟度（1～5）/)).not.toBeInTheDocument()
    expect(screen.queryByText('85')).not.toBeInTheDocument()
    expect(screen.queryByText(/本人 4/)).not.toBeInTheDocument()
    expect(screen.getByText('下書き')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('radio', { name: 'A' })[0])
    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))
    await waitFor(() => expect(apiMock.mock.calls.some(([path, init]) => path === '/api/v1/manager-evaluations/T1' && init?.method === 'PUT')).toBe(true))
  })

  it('上長の下書き保存はrankと現在versionをAPIへ送る', async () => {
    apiMock.mockResolvedValue(draftManagerEvaluation)
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })

    fireEvent.click(screen.getAllByRole('radio', { name: 'A' })[0])
    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))

    await waitFor(() => expect(apiMock.mock.calls.some(([path, init]) => path === '/api/v1/manager-evaluations/T1' && init?.method === 'PUT')).toBe(true))
    const [, init] = apiMock.mock.calls.find(([path, request]) => path === '/api/v1/manager-evaluations/T1' && request?.method === 'PUT') as [string, RequestInit]
    const body = JSON.parse(String(init.body)) as { version: number; details: Array<{ axisCode: string; rank: string }> }
    expect(body.version).toBe(4)
    expect(body.details[0]).toEqual(expect.objectContaining({ axisCode: 'AXIS_0', rank: 'A' }))
    expect(body.details).toHaveLength(6)
  })

  it('上長提出はrankを保存して返された最新versionで最終承認へ進める', async () => {
    apiMock.mockResolvedValueOnce(draftManagerEvaluation)
      .mockResolvedValueOnce({ ...draftManagerEvaluation, status: 'MANAGER_IN_PROGRESS', targetVersion: 5 })
      .mockResolvedValue({ ...managerEvaluation, status: 'EXECUTIVE_REVIEW', targetVersion: 6 })
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })

    const submitButton = screen.getByRole('button', { name: '最終承認者へ提出' })
    await waitFor(() => expect(submitButton).toBeEnabled())
    fireEvent.click(submitButton)

    await waitFor(() => expect(apiMock.mock.calls.some(([path]) => path === '/api/v1/manager-evaluations/T1/submit')).toBe(true))
    const putCall = apiMock.mock.calls.find(([path, request]) => path === '/api/v1/manager-evaluations/T1' && request?.method === 'PUT') as [string, RequestInit]
    const submitCall = apiMock.mock.calls.find(([path]) => path === '/api/v1/manager-evaluations/T1/submit') as [string, RequestInit]
    expect(JSON.parse(String(putCall[1].body)).details[0]).toEqual(expect.objectContaining({ rank: 'S' }))
    expect(JSON.parse(String(submitCall[1].body))).toEqual({ version: 5 })
  })

  it('MANAGER_RETURNEDは上長が再編集・再提出し、本人差戻しUIを表示しない', async () => {
    apiMock.mockResolvedValueOnce(managerEvaluation)
      .mockResolvedValueOnce({ ...managerEvaluation, status: 'MANAGER_IN_PROGRESS', targetVersion: 5 })
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })
    expect(screen.queryByLabelText('本人への差戻し理由')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '本人へ差し戻す' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下書き保存' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '最終承認者へ提出' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))
    expect(await screen.findByText('上長入力中')).toBeInTheDocument()
  })

  it('提出前保存後のPOST失敗で最新versionを保持し、再試行を古いversionで送らない', async () => {
    apiMock.mockResolvedValueOnce(draftManagerEvaluation)
      .mockResolvedValueOnce({ ...managerEvaluation, status: 'MANAGER_IN_PROGRESS', targetVersion: 5 })
      .mockRejectedValueOnce(new Error('送信に失敗しました'))
      .mockResolvedValueOnce({ ...managerEvaluation, status: 'MANAGER_IN_PROGRESS', targetVersion: 6 })
      .mockResolvedValueOnce({ ...managerEvaluation, status: 'EXECUTIVE_REVIEW', targetVersion: 7 })
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })

    fireEvent.click(screen.getByRole('button', { name: '最終承認者へ提出' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('処理を完了できませんでした')
    fireEvent.click(screen.getByRole('button', { name: '最終承認者へ提出' }))

    await screen.findByText('最終承認者へ提出しました。')
    const putVersions = apiMock.mock.calls
      .filter(([path, init]) => path === '/api/v1/manager-evaluations/T1' && init?.method === 'PUT')
      .map(([, init]) => JSON.parse(String(init?.body)).version)
    const submitVersions = apiMock.mock.calls
      .filter(([path]) => path === '/api/v1/manager-evaluations/T1/submit')
      .map(([, init]) => JSON.parse(String(init?.body)).version)
    expect(putVersions).toEqual([4, 5])
    expect(submitVersions).toEqual([5, 6])
  })

  it('提出処理中の二重操作でPUTとPOSTを重複送信しない', async () => {
    let resolveSave!: (value: typeof managerEvaluation) => void
    const savePending = new Promise<typeof managerEvaluation>((resolve) => { resolveSave = resolve })
    apiMock.mockResolvedValueOnce(draftManagerEvaluation)
      .mockImplementationOnce(() => savePending)
      .mockResolvedValueOnce({ ...managerEvaluation, status: 'EXECUTIVE_REVIEW', targetVersion: 6 })
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })

    const submit = screen.getByRole('button', { name: '最終承認者へ提出' })
    fireEvent.click(submit)
    await waitFor(() => expect(apiMock.mock.calls.filter(([path, init]) => path === '/api/v1/manager-evaluations/T1' && init?.method === 'PUT')).toHaveLength(1))
    fireEvent.click(submit)
    expect(apiMock.mock.calls.filter(([path, init]) => path === '/api/v1/manager-evaluations/T1' && init?.method === 'PUT')).toHaveLength(1)

    await act(async () => resolveSave({ ...managerEvaluation, status: 'MANAGER_IN_PROGRESS', targetVersion: 5 }))
    await waitFor(() => expect(apiMock.mock.calls.filter(([path]) => path === '/api/v1/manager-evaluations/T1/submit')).toHaveLength(1))
  })

  it('409競合の詳細を表示し古いversionで成功扱いにしない', async () => {
    apiMock.mockResolvedValueOnce(managerEvaluation).mockRejectedValueOnce(new ApiError({
      title: '競合', detail: '他の利用者が更新しました。最新状態を再取得してください。',
      status: 409, code: 'VERSION_CONFLICT',
    }))
    renderRoute(<ManagerEvaluationDetailPage />, '/evaluations/manager/:publicId', '/evaluations/manager/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの上長評価' })
    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('最新状態を再取得してください')
    expect(screen.queryByText('上長評価を保存しました。')).not.toBeInTheDocument()
  })

  it('経営者の判断画面にも評価基準を表示し数値scoreとlevelを見せない', async () => {
    apiMock.mockResolvedValue(executiveEvaluation)
    renderRoute(<ExecutiveEvaluationDetailPage />, '/executive/evaluations/:publicId', '/executive/evaluations/T1')

    expect(await screen.findByRole('heading', { name: '山田 太郎さんの最終評価' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '評価基準と注意事項' })).toBeInTheDocument()
    expect(screen.getByLabelText('上長評価の総合ランク A')).toBeInTheDocument()
    expect(screen.queryByText('85')).not.toBeInTheDocument()
    expect(screen.queryByText(/本人 4/)).not.toBeInTheDocument()
    expect(screen.getByText('最終承認待ち')).toBeInTheDocument()
  })

  it.each([
    ['approve', 'EXECUTIVE_REVIEW', '最終承認', null],
    ['return', 'EXECUTIVE_REVIEW', '上長へ差し戻す', '判断根拠を追加してください。'],
    ['reopen', 'FINALIZED', '理由を記録して再オープン', '確定内容を訂正します。'],
  ])('経営者の%s操作は現在versionを維持する', async (action, status, buttonName, reason) => {
    apiMock.mockResolvedValue({ ...executiveEvaluation, status })
    renderRoute(<ExecutiveEvaluationDetailPage />, '/executive/evaluations/:publicId', '/executive/evaluations/T1')
    await screen.findByRole('heading', { name: '山田 太郎さんの最終評価' })
    if (reason) fireEvent.change(screen.getByLabelText('差戻し・再オープン理由（必須）'), { target: { value: reason } })
    fireEvent.click(screen.getByRole('button', { name: buttonName }))

    const path = `/api/v1/executive/evaluations/T1/${action}`
    await waitFor(() => expect(apiMock.mock.calls.some(([calledPath]) => calledPath === path)).toBe(true))
    const call = apiMock.mock.calls.find(([calledPath]) => calledPath === path) as [string, RequestInit]
    expect(JSON.parse(String(call[1].body))).toEqual(reason ? { version: 4, reason } : { version: 4, comment: '' })
  })

  it('全社役職者切替時に前利用者の判断詳細をキャッシュ表示しない', async () => {
    apiMock.mockResolvedValueOnce({ ...executiveEvaluation, employeeName: '前利用者の対象' })
      .mockResolvedValue({ ...executiveEvaluation, employeeName: '次利用者の対象' })
    const client = queryClient()
    const first = renderRoute(<ExecutiveEvaluationDetailPage />, '/executive/evaluations/:publicId', '/executive/evaluations/T1', client)
    expect(await screen.findByRole('heading', { name: '前利用者の対象さんの最終評価' })).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: { ...officer, accountPublicId: 'ACCOUNT-2', employeePublicId: 'EMPLOYEE-2', scopes: ['ALL'] }, loading: false, logout: vi.fn() })
    first.rerender(routeTree(<ExecutiveEvaluationDetailPage />, '/executive/evaluations/:publicId', '/executive/evaluations/T1', client))

    expect(screen.queryByRole('heading', { name: '前利用者の対象さんの最終評価' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '次利用者の対象さんの最終評価' })).toBeInTheDocument()
  })

  it('経営者には承認待ち件数と全社一覧を表示する', async () => {
    apiMock.mockResolvedValue({ counts: { total: 51, pending: 2, finalized: 8, overdue: 3 }, items: [], distributions: [] })
    renderPage(<ExecutiveDashboardPage />)
    expect(await screen.findByText('2')).toBeInTheDocument()
    expect(screen.getByText('最終承認待ち', { selector: 'span' })).toBeInTheDocument()
  })

  it('経営者一覧は6rankで絞り込み、日本語状態と総合ランクだけを表示する', async () => {
    apiMock.mockResolvedValue({
      counts: { total: 1, pending: 1, finalized: 0, overdue: 0 },
      items: [{ publicId: 'T1', employeeName: '山田 太郎', departmentName: '開発', status: 'EXECUTIVE_REVIEW', version: 4, finalGrade: null, managerGrade: 'F', periodName: '2026年度', late: false }],
      distributions: [],
    })
    renderPage(<ExecutiveDashboardPage />)

    expect(await screen.findByText('山田 太郎')).toBeInTheDocument()
    expect(screen.getByText('最終承認待ち', { selector: 'span.status' })).toBeInTheDocument()
    expect(screen.queryByText('EXECUTIVE_REVIEW')).not.toBeInTheDocument()
    expect(screen.queryByText('FINAL REVIEW')).not.toBeInTheDocument()
    expect(screen.queryByText('85')).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '総合ランク' })).toBeInTheDocument()
    const gradeFilter = screen.getByRole('combobox', { name: '総合ランク' })
    for (const rank of ['S', 'A', 'B', 'C', 'D', 'F']) {
      expect(gradeFilter).toContainElement(screen.getByRole('option', { name: rank }))
    }
    fireEvent.change(gradeFilter, { target: { value: 'D' } })
    expect(screen.queryByText('山田 太郎')).not.toBeInTheDocument()
    fireEvent.change(gradeFilter, { target: { value: 'F' } })
    expect(screen.getByText('山田 太郎').closest('tr')).toHaveTextContent('F')
  })

  it('経営者一覧は読込中、取得失敗と再試行、空一覧を別のARIA状態で表示する', async () => {
    let rejectLoad!: (reason: Error) => void
    const loading = new Promise((_, reject) => { rejectLoad = reject })
    apiMock.mockImplementationOnce(() => loading).mockResolvedValueOnce({
      counts: { total: 0, pending: 0, finalized: 0, overdue: 0 }, items: [], distributions: [],
    })
    renderPage(<ExecutiveDashboardPage />)

    expect(screen.getByRole('status', { name: '全社評価を読込中' })).toHaveAttribute('aria-busy', 'true')
    await act(async () => rejectLoad(new Error('network')))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('全社評価を取得できませんでした')
    fireEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('status', { name: '全社評価の状態' })).toHaveTextContent('評価対象はありません')
  })

  it('全社役職者切替時に前利用者の全社一覧をキャッシュ表示しない', async () => {
    const dashboard = (employeeName: string) => ({
      counts: { total: 1, pending: 1, finalized: 0, overdue: 0 },
      items: [{ publicId: 'T1', employeeName, departmentName: '開発', status: 'EXECUTIVE_REVIEW', version: 4, finalGrade: null, managerGrade: 'C', periodName: '2026年度', late: false }],
      distributions: [],
    })
    apiMock.mockResolvedValueOnce(dashboard('前利用者の全社対象')).mockResolvedValue(dashboard('次利用者の全社対象'))
    const client = queryClient()
    const first = renderPage(<ExecutiveDashboardPage />, client)
    expect(await screen.findByText('前利用者の全社対象')).toBeInTheDocument()

    useAuthMock.mockReturnValue({ user: { ...officer, accountPublicId: 'ACCOUNT-2', employeePublicId: 'EMPLOYEE-2', scopes: ['ALL'] }, loading: false, logout: vi.fn() })
    first.rerender(pageTree(<ExecutiveDashboardPage />, client))

    expect(screen.queryByText('前利用者の全社対象')).not.toBeInTheDocument()
    expect(await screen.findByText('次利用者の全社対象')).toBeInTheDocument()
  })
})

function renderPage(element: React.ReactNode, client = queryClient()) {
  return render(pageTree(element, client))
}

function pageTree(element: React.ReactNode, client: QueryClient) {
  return <QueryClientProvider client={client}><MemoryRouter>{element}</MemoryRouter></QueryClientProvider>
}

function renderRoute(element: React.ReactNode, route: string, entry: string, client = queryClient()) {
  return render(routeTree(element, route, entry, client))
}

function routeTree(element: React.ReactNode, route: string, entry: string, client: QueryClient) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>
    </QueryClientProvider>
  )
}

function queryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}
