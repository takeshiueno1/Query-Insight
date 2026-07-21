import { useEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { api, setAccessToken } from '../../lib/api'
import type { User } from '../../types'
import { AuthContext, type AuthContextValue } from './auth-context'

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api<{ accessToken: string; user: User }>('/api/v1/auth/refresh', { method: 'POST' }, false)
      .then((session) => {
        setAccessToken(session.accessToken)
        setUser(session.user)
      })
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
  }, [])

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    login: async (loginId, password) => {
      const session = await api<{ accessToken: string; user: User }>('/api/v1/auth/login', {
        method: 'POST', body: JSON.stringify({ loginId, password }),
      }, false)
      setAccessToken(session.accessToken)
      setUser(session.user)
    },
    logout: async () => {
      await api<void>('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined)
      setAccessToken(null)
      setUser(null)
    },
  }), [loading, user])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
