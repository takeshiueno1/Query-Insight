import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TalentSubmissionFormPage } from './TalentSubmissionFormPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock, ApiError: class extends Error {} }))

function renderType(type: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<MemoryRouter initialEntries={[`/talent/new/${type}`]}><QueryClientProvider client={client}><Routes><Route path="/talent/new/:type" element={<TalentSubmissionFormPage />} /></Routes></QueryClientProvider></MemoryRouter>)
}

describe('TalentSubmissionFormPage', () => {
  beforeEach(() => apiMock.mockReset().mockResolvedValue([]))
  afterEach(cleanup)

  it.each([
    ['SKILL', '経験年数'], ['KNOWLEDGE', '習熟度（1～5）'], ['CAREER', '案件名'], ['CERTIFICATION', '取得日'],
  ])('%sの種類別入力欄を表示する', (type, label) => {
    renderType(type)
    expect(screen.getByLabelText(label)).toBeInTheDocument()
  })

  it.each([
    ['SKILL', 'スキルを登録', 'スキル'],
    ['KNOWLEDGE', '得意分野を登録', '得意分野'],
    ['CAREER', '業務経歴を登録', '案件名'],
    ['CERTIFICATION', '資格を登録', '資格'],
  ])('%sの見出しと入力名を自然な日本語で表示し内部名称を見せない', (type, heading, label) => {
    renderType(type)

    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByLabelText(label)).toBeInTheDocument()
    expect(screen.queryByText('専門知識')).not.toBeInTheDocument()
    expect(screen.queryByText('TALENT APPLICATION')).not.toBeInTheDocument()
    expect(screen.queryByText(type)).not.toBeInTheDocument()
  })

  it('4件または5MB超の添付をAPI送信前に拒否する', () => {
    renderType('CAREER')
    const input = screen.getByLabelText('根拠資料（任意）') as HTMLInputElement
    const files = [1, 2, 3, 4].map((value) => new File(['pdf'], `${value}.pdf`, { type: 'application/pdf' }))
    fireEvent.change(input, { target: { files } })
    expect(screen.getByRole('status')).toHaveTextContent('添付は3件まで')
    expect(apiMock).not.toHaveBeenCalled()

    const large = new File(['pdf'], 'large.pdf', { type: 'application/pdf' })
    Object.defineProperty(large, 'size', { value: 5_242_881 })
    fireEvent.change(input, { target: { files: [large] } })
    expect(screen.getByRole('status')).toHaveTextContent('1件5MB以内')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('根拠資料は任意で添付なしのまま提出できる', async () => {
    apiMock
      .mockResolvedValueOnce({ publicId: 'S1', logicalPublicId: 'L1', type: 'CAREER', revisionNo: 1, status: 'DRAFT', version: 0 })
      .mockResolvedValueOnce({ publicId: 'S1', logicalPublicId: 'L1', type: 'CAREER', revisionNo: 1, status: 'SUBMITTED', version: 1 })
    renderType('CAREER')
    expect(screen.getByLabelText('根拠資料（任意）')).toBeInTheDocument()
    for (const [label, value] of [
      ['案件名', '基幹刷新'], ['業界', '製造'], ['役割', '担当者'], ['開始日', '2025-04-01'],
      ['概要', '刷新を担当'], ['成果', '予定どおり完了'], ['利用技術', 'Java'],
    ]) fireEvent.change(screen.getByLabelText(label), { target: { value } })
    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))
    expect(apiMock).toHaveBeenCalledTimes(2)
    expect(apiMock.mock.calls.some(([url]) => String(url).includes('/attachments'))).toBe(false)
    expect(screen.getByText(/状態: 申請中/)).toBeInTheDocument()
    expect(screen.queryByText('SUBMITTED')).not.toBeInTheDocument()
  })
})
