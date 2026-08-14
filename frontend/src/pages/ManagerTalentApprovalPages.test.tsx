import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ManagerTalentApprovalDetailPage } from './ManagerTalentApprovalDetailPage'
import { ManagerTalentApprovalsPage } from './ManagerTalentApprovalsPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))

describe('ManagerTalentApprovalDetailPage', () => {
  beforeEach(() => apiMock.mockReset().mockImplementation((path: string) => {
    if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
    return Promise.resolve({
      submission: { publicId: '01K00000000000000000000000', logicalPublicId: '01K00000000000000000000000', type: 'SKILL', revisionNo: 1, status: 'SUBMITTED', version: 2, returnReason: null, payload: { masterPublicId: 'MASTER-1', level: 4, yearsExperience: 5, lastUsedOn: '2026-08-01', evidence: '業務実績' }, submittedAt: '2026-08-12T00:00:00Z', decidedAt: null },
      employee: { publicId: 'E1', displayName: 'テスト ユーザー' }, approvedPredecessorPayload: { masterPublicId: 'MASTER-1', level: 3, yearsExperience: 3, lastUsedOn: '2025-08-01', evidence: '以前の実績' }, attachments: [], events: [],
    })
  }))
  afterEach(cleanup)

  it('申請値を日本語の構造化項目で読み取り専用表示し内部値を見せない', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<MemoryRouter initialEntries={['/approvals/talent/01K00000000000000000000000']}><QueryClientProvider client={client}><Routes><Route path="/approvals/talent/:publicId" element={<ManagerTalentApprovalDetailPage />} /></Routes></QueryClientProvider></MemoryRouter>)
    expect(await screen.findAllByText('Java')).toHaveLength(2)
    expect(screen.getAllByText('習熟度')).toHaveLength(2)
    expect(screen.getByText('申請中')).toBeInTheDocument()
    expect(screen.queryByText(/SKILL|SUBMITTED|masterPublicId|"level"|\{/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '差し戻す' })).toBeDisabled()
    expect(screen.queryByDisplayValue('4')).not.toBeInTheDocument()
  })
})

describe('ManagerTalentApprovalsPage', () => {
  afterEach(cleanup)

  it('申請一覧の読込中を支援技術へ通知する', () => {
    apiMock.mockReset().mockImplementation(() => new Promise(() => undefined))
    renderManagerList()

    expect(screen.getByRole('status')).toHaveTextContent('タレント申請を読み込んでいます')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('申請一覧の取得失敗を支援技術へ通知する', async () => {
    apiMock.mockReset().mockRejectedValue(new Error('取得失敗'))
    renderManagerList()

    expect(await screen.findByRole('alert')).toHaveTextContent('タレント申請を取得できませんでした')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('対象申請がない場合は日本語の空状態を表示する', async () => {
    apiMock.mockReset().mockResolvedValue([])
    renderManagerList()

    expect(await screen.findByText('確認待ちのタレント申請はありません。')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('申請種類を日本語で表示し内部type codeを見せない', async () => {
    apiMock.mockReset().mockResolvedValue([{ publicId: 'S1', type: 'KNOWLEDGE', status: 'SUBMITTED', version: 2, submittedAt: '2026-08-12T00:00:00Z', employeePublicId: 'E1', employeeName: 'テスト ユーザー' }])
    renderManagerList()

    expect(await screen.findByText('得意分野')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.queryByText('KNOWLEDGE')).not.toBeInTheDocument()
    expect(screen.queryByText('OFFICER APPROVAL')).not.toBeInTheDocument()
  })
})

function renderManagerList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<MemoryRouter><QueryClientProvider client={client}><ManagerTalentApprovalsPage /></QueryClientProvider></MemoryRouter>)
}
