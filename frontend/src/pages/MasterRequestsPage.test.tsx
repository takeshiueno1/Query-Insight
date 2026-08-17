import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MasterRequestsPage } from './MasterRequestsPage'

const { apiMock, useAuthMock } = vi.hoisted(() => ({ apiMock: vi.fn(), useAuthMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MasterRequestsPage /></QueryClientProvider>)
}

describe('MasterRequestsPage', () => {
  beforeEach(() => {
    apiMock.mockReset().mockResolvedValue([])
    useAuthMock.mockReturnValue({
      user: { accountPublicId: 'A1', employeePublicId: 'E1', displayName: '申請 太郎', roles: ['GENERAL'], scopes: ['SELF'] },
    })
  })
  afterEach(cleanup)

  it('一般ユーザーには種類と説明だけを入力させる', () => {
    renderPage()

    expect(screen.getByLabelText('種類')).toBeInTheDocument()
    expect(screen.getByLabelText('説明')).toBeInTheDocument()
    expect(screen.queryByLabelText('コード')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('名称')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('カテゴリ')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('発行団体')).not.toBeInTheDocument()
  })

  it('trimした2項目だけをAPIへ送信する', async () => {
    apiMock.mockImplementation((path: string, options?: RequestInit) => Promise.resolve(options
      ? { publicId: 'R1', type: '資格', description: 'AWS認定を追加したい', status: 'SUBMITTED', version: 0, returnReason: null, requestedAt: '2026-08-14T00:00:00Z', requesterName: '申請 太郎' }
      : path.endsWith('/me') ? [] : []))
    renderPage()

    fireEvent.change(screen.getByLabelText('種類'), { target: { value: '  資格  ' } })
    fireEvent.change(screen.getByLabelText('説明'), { target: { value: '  AWS認定を追加したい  ' } })
    fireEvent.click(screen.getByRole('button', { name: '申請する' }))

    await waitFor(() => expect(apiMock).toHaveBeenCalledWith('/api/v1/master-requests', {
      method: 'POST',
      body: JSON.stringify({ type: '資格', description: 'AWS認定を追加したい' }),
    }))
  })

  it.each([
    ['空の種類', '', '有効な説明', '種類', '種類を入力してください'],
    ['101文字の種類', 'T'.repeat(101), '有効な説明', '種類', '種類は100文字以内で入力してください'],
    ['空の説明', '資格', '', '説明', '説明を入力してください'],
    ['1001文字の説明', '資格', 'D'.repeat(1001), '説明', '説明は1000文字以内で入力してください'],
  ])('%sは入力欄に接続した日本語エラーを表示する', async (_case, type, description, field, message) => {
    renderPage()
    fireEvent.change(screen.getByLabelText('種類'), { target: { value: type } })
    fireEvent.change(screen.getByLabelText('説明'), { target: { value: description } })
    fireEvent.click(screen.getByRole('button', { name: '申請する' }))

    const error = await screen.findByText(message)
    const input = screen.getByLabelText(field)
    expect(error).toHaveAttribute('role', 'alert')
    expect(error.id).not.toBe('')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', error.id)
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/master-requests', expect.anything())
  })

  it('履歴を日本語の状態と2項目で表示し内部JSONを表示しない', async () => {
    apiMock.mockResolvedValue([{
      publicId: 'R2', type: 'クラウド資格', description: 'AWS認定の追加を希望します', status: 'RETURNED',
      version: 1, returnReason: '対象資格を明記してください', requestedAt: '2026-08-14T00:00:00Z',
      requesterName: '申請 太郎', createdMasterPublicId: null,
    }])
    renderPage()

    expect(await screen.findByText('クラウド資格')).toBeInTheDocument()
    expect(screen.getByText('AWS認定の追加を希望します')).toBeInTheDocument()
    expect(screen.getByText(/状態: 差戻し/)).toBeInTheDocument()
    expect(screen.getByText(/差戻し理由: 対象資格を明記してください/)).toBeInTheDocument()
    expect(document.querySelector('pre')).not.toBeInTheDocument()
  })

  it('管理者には申請者と日時と判断操作を表示する', async () => {
    useAuthMock.mockReturnValue({
      user: { accountPublicId: 'A2', employeePublicId: 'E2', displayName: '管理 花子', roles: ['ADMIN'], scopes: ['ALL'] },
    })
    apiMock.mockImplementation((path: string) => Promise.resolve(path.includes('/admin/') ? [{
      publicId: 'R3', type: '技術カテゴリ', description: '新カテゴリを提案します', status: 'SUBMITTED',
      version: 0, returnReason: null, requestedAt: '2026-08-14T00:00:00Z', requesterName: '申請 太郎',
      createdMasterPublicId: null,
    }] : []))
    renderPage()

    expect(await screen.findByText('新カテゴリを提案します')).toBeInTheDocument()
    expect(screen.getByText('申請者: 申請 太郎')).toBeInTheDocument()
    expect(screen.getByText(/申請日時:/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '承認する' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '差し戻す' })).toBeDisabled()
    expect(document.querySelector('pre')).not.toBeInTheDocument()
  })
})
