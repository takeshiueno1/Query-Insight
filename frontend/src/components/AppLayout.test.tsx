import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../types'
import { AppLayout } from './AppLayout'

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({ useAuth: useAuthMock }))

describe('権限別ナビゲーション', () => {
  beforeEach(() => useAuthMock.mockReset())
  afterEach(cleanup)

  it.each([
    ['一般', user('GENERAL', 'SELF'), [], ['社員検索', '上長評価', 'タレント承認', '最終承認', '監査']],
    ['直属部下の役職者', user('OFFICER', 'SUBORDINATES'), ['社員検索', '上長評価', 'タレント承認'], ['最終承認', '監査']],
    ['全社の役職者', user('OFFICER', 'ALL'), ['社員検索', '最終承認'], ['上長評価', 'タレント承認', '監査']],
    ['管理者', user('ADMIN', 'ALL'), ['社員検索', '監査'], ['上長評価', 'タレント承認', '最終承認']],
  ])('%sに許可されたメニューだけを表示する', (_label, currentUser, visible, hidden) => {
    useAuthMock.mockReturnValue({ user: currentUser, loading: false, logout: vi.fn() })
    render(<MemoryRouter><AppLayout /></MemoryRouter>)

    for (const label of visible) expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    for (const label of hidden) expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument()
  })
})

function user(role: User['roles'][number], scope: User['scopes'][number]): User {
  return { accountPublicId: 'A', employeePublicId: 'E', displayName: '試験 利用者', roles: [role], scopes: [scope] }
}
