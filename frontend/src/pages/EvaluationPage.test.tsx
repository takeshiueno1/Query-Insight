import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FinalEvaluationResult, SelfEvaluation } from '../types'
import { EvaluationPage } from './EvaluationPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, api: apiMock }
})

const submittedEvaluation: SelfEvaluation = {
  publicId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
  periodName: '2026年度 下期評価',
  status: 'SELF_SUBMITTED',
  version: 1,
  details: ['技術力', '設計力', '業務理解', '説明力', '推進力', '改善力'].map((displayName, index) => ({
    axisCode: `AXIS_${index}`,
    displayName,
    description: `${displayName}の説明`,
    level: 4,
    evidence: `${displayName}の根拠`,
  })),
}

describe('EvaluationPage', () => {
  beforeEach(() => apiMock.mockReset())

  it('提出済み評価を参照専用で表示する', async () => {
    apiMock.mockImplementation((path?: string) => Promise.resolve(path?.endsWith('/final-result')
      ? { status: 'SELF_SUBMITTED', finalScore: null, finalGrade: null, summary: null, details: [] }
      : submittedEvaluation))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><EvaluationPage /></QueryClientProvider>)

    expect(await screen.findByText('提出済みのため、現在は参照のみ可能です。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下書き保存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '根拠を確認して提出' })).toBeDisabled()
    screen.getAllByRole('textbox').forEach((textbox) => expect(textbox).toBeDisabled())
    expect(screen.queryByText('上長だけが知る総評')).not.toBeInTheDocument()
  })

  it('最終承認後だけ確定評価と上長総評を表示する', async () => {
    const result: FinalEvaluationResult = { status: 'FINALIZED', finalScore: 4.25, finalGrade: 'A', summary: '上長だけが知る総評', details: [{ axisCode: 'TECHNICAL', displayName: '技術力', selfLevel: 4, managerLevel: 5, comment: '成果を確認' }] }
    apiMock.mockImplementation((path?: string) => Promise.resolve(path?.endsWith('/final-result') ? result : submittedEvaluation))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><EvaluationPage /></QueryClientProvider>)

    expect(await screen.findByText('確定評価')).toBeInTheDocument()
    expect(screen.getByText('上長だけが知る総評')).toBeInTheDocument()
    expect(screen.getByText('4.25')).toBeInTheDocument()
  })
})
