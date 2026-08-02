import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ExecutiveDashboardPage } from './ExecutiveDashboardPage'
import { ManagerEvaluationsPage } from './ManagerEvaluationsPage'

const { useQueryMock } = vi.hoisted(() => ({ useQueryMock: vi.fn() }))
vi.mock('@tanstack/react-query', () => ({ useQuery: useQueryMock }))

describe('評価承認画面', () => {
  beforeEach(() => useQueryMock.mockReset())

  it('上長には担当者と期限超過を表示する', () => {
    useQueryMock.mockReturnValue({ isLoading: false, data: [{ publicId: 'T1', employeeName: '山田 太郎', departmentName: '開発', periodName: '2026年度', status: 'SELF_SUBMITTED', version: 0, late: true }] })
    render(<MemoryRouter><ManagerEvaluationsPage /></MemoryRouter>)
    expect(screen.getByText('山田 太郎')).toBeInTheDocument()
    expect(screen.getByText('期限超過', { selector: 'span' })).toBeInTheDocument()
  })

  it('経営者には承認待ち件数と全社一覧を表示する', () => {
    useQueryMock.mockReturnValue({ isLoading: false, data: { counts: { total: 51, pending: 2, finalized: 8, overdue: 3 }, items: [], distributions: [] } })
    render(<MemoryRouter><ExecutiveDashboardPage /></MemoryRouter>)
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('最終承認待ち', { selector: 'span' })).toBeInTheDocument()
  })
})
