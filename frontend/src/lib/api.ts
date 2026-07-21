import type { Problem } from '../types'

let accessToken: string | null = null
let refreshPromise: Promise<boolean> | null = null

export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail)
    this.problem = problem
  }
}

export function setAccessToken(token: string | null) {
  accessToken = token
}

async function refresh(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(async (response) => {
        if (!response.ok) return false
        const body = (await response.json()) as { accessToken: string }
        setAccessToken(body.accessToken)
        return true
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...init, headers, credentials: 'include' })
  if (response.status === 401 && retry && await refresh()) return api<T>(path, init, false)
  if (!response.ok) {
    const fallback: Problem = { title: '通信エラー', detail: '処理を完了できませんでした', status: response.status, code: 'HTTP_ERROR' }
    throw new ApiError(await response.json().catch(() => fallback) as Problem)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
