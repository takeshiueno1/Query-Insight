import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ManagerTalentApprovalDetailPage } from './ManagerTalentApprovalDetailPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))

describe('ManagerTalentApprovalDetailPage', () => {
  beforeEach(() => apiMock.mockReset().mockResolvedValue({
    submission: { publicId: '01K00000000000000000000000', logicalPublicId: '01K00000000000000000000000', type: 'SKILL', revisionNo: 1, status: 'SUBMITTED', version: 2, returnReason: null, payload: { level: 4 }, submittedAt: '2026-08-12T00:00:00Z', decidedAt: null },
    employee: { publicId: 'E1', displayName: 'テスト ユーザー' }, approvedPredecessorPayload: { level: 3 }, attachments: [], events: [],
  }))

  it('申請値を読み取り専用で表示し理由入力前は差戻しできない', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<MemoryRouter initialEntries={['/approvals/talent/01K00000000000000000000000']}><QueryClientProvider client={client}><Routes><Route path="/approvals/talent/:publicId" element={<ManagerTalentApprovalDetailPage />} /></Routes></QueryClientProvider></MemoryRouter>)
    expect(await screen.findByText(/"level": 4/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '差し戻す' })).toBeDisabled()
    expect(screen.queryByDisplayValue('4')).not.toBeInTheDocument()
  })
})
