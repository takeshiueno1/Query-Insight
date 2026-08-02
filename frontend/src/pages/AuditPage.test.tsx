import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../lib/api'
import { AuditPage } from './AuditPage'

const { useQueryMock } = vi.hoisted(() => ({ useQueryMock: vi.fn() }))
vi.mock('@tanstack/react-query', () => ({ useQuery: useQueryMock }))

describe('AuditPage', () => {
  beforeEach(() => useQueryMock.mockReset())

  it('取得失敗時に問い合わせID付きのエラーを表示する', () => {
    useQueryMock.mockReturnValue({
      data: undefined,
      error: new ApiError({
        title: 'システムエラー',
        detail: '監査ログを取得できませんでした',
        status: 500,
        code: 'INTERNAL_ERROR',
        traceId: 'TEST-TRACE-ID',
      }),
      isError: true,
      isLoading: false,
    })

    render(<AuditPage />)

    expect(screen.getByRole('alert')).toHaveTextContent('監査ログを取得できませんでした')
    expect(screen.getByRole('alert')).toHaveTextContent('TEST-TRACE-ID')
    expect(screen.queryByText('監査記録はありません。')).not.toBeInTheDocument()
  })
})
