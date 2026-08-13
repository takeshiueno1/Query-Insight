import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'

const { loginMock } = vi.hoisted(() => ({ loginMock: vi.fn() }))
vi.mock('../features/auth/auth-context', () => ({
  useAuth: () => ({ user: null, login: loginMock }),
}))

function renderLogin() {
  return render(<MemoryRouter><LoginPage /></MemoryRouter>)
}

function submit(password: string) {
  fireEvent.change(screen.getByLabelText('ログインID'), { target: { value: 'ueno' } })
  fireEvent.change(screen.getByLabelText('パスワード'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'ログイン' }))
}

describe('LoginPage password policy', () => {
  beforeEach(() => loginMock.mockReset().mockResolvedValue(undefined))
  afterEach(cleanup)

  it('半角英数字8文字でログイン処理を呼ぶ', async () => {
    renderLogin()
    submit('5050Rock')

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('ueno', '5050Rock'))
    expect(screen.queryByText(/パスワードは/)).not.toBeInTheDocument()
  })

  it.each(['505Rock', 'RockOnly', '50505050', '5050#Rock', '５０５０Rock', '5050 Rock'])(
    'ポリシー外のパスワード %s を送信前に拒否する',
    async (password) => {
      renderLogin()
      submit(password)

      expect(await screen.findByText(/パスワードは半角英数字/)).toBeInTheDocument()
      expect(loginMock).not.toHaveBeenCalled()
    },
  )
})
