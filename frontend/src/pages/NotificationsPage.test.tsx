import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationsPage } from './NotificationsPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({ api: apiMock }))

describe('NotificationsPage', () => {
  beforeEach(() => apiMock.mockReset())

  it('通知から役割別の評価詳細へ遷移できる', async () => {
    apiMock.mockResolvedValue([{ publicId: 'N1', type: 'EXECUTIVE_REVIEW', title: '最終承認をお願いします', body: '評価が届きました', linkPath: '/executive/evaluations/T1', readAt: null, createdAt: '2026-08-02T00:00:00Z' }])
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<MemoryRouter><QueryClientProvider client={client}><NotificationsPage /></QueryClientProvider></MemoryRouter>)

    expect(await screen.findByRole('link', { name: '最終承認をお願いします' })).toHaveAttribute('href', '/executive/evaluations/T1')
  })
})
