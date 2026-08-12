import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
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

  it('4件または5MB超の添付をAPI送信前に拒否する', () => {
    renderType('CAREER')
    const input = screen.getByLabelText(/根拠ファイル/) as HTMLInputElement
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
})
