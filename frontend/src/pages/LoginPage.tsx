import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate } from 'react-router-dom'
import { z } from 'zod'
import { BrandLogo } from '../components/BrandLogo'
import { useAuth } from '../features/auth/auth-context'
import { ApiError } from '../lib/api'

const schema = z.object({
  loginId: z.string().trim().min(1, 'ログインIDを入力してください').max(254),
  password: z.string()
    .min(8, 'パスワードは半角英数字8文字以上です')
    .max(128)
    .regex(/^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]+$/, 'パスワードは半角英数字で、英字と数字を含めてください'),
})
type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const { user, login } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) })
  if (user) return <Navigate to="/" replace />
  return (
    <div className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-logo brand-plate"><BrandLogo /></div>
        <div className="login-heading"><h1 id="login-title">ログイン</h1><p>社員番号またはメールアドレス</p></div>
        <form onSubmit={handleSubmit(async (values) => {
          setError(null)
          try { await login(values.loginId, values.password) }
          catch (reason) { setError(reason instanceof ApiError ? reason.problem.detail : 'ログインできませんでした') }
        })} noValidate>
          <label>ログインID<input autoComplete="username" {...register('loginId')} /></label>
          {errors.loginId && <span className="field-error">{errors.loginId.message}</span>}
          <label>パスワード<input type="password" autoComplete="current-password" {...register('password')} /></label>
          {errors.password && <span className="field-error">{errors.password.message}</span>}
          {error && <div className="error-banner" role="alert">{error}</div>}
          <button className="primary-button full" disabled={isSubmitting}>{isSubmitting ? '確認中…' : 'ログイン'}</button>
        </form>
      </section>
    </div>
  )
}
