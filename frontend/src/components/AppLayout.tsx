import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'
import { api } from '../lib/api'
import type { DataScope, Role } from '../types'
import { BrandLogo } from './BrandLogo'
import { NotificationBadge } from './NotificationBadge'
import { NavIcon, type NavIconName } from './NavIcon'
import { LiveDateTime } from './LiveDateTime'

type NavItem = { to: string; label: string; icon: NavIconName; role?: Role; scope?: DataScope }
const nav: NavItem[] = [
  { to: '/', label: 'ダッシュボード', icon: 'dashboard' },
  { to: '/employees', label: '社員検索', icon: 'employees', role: 'OFFICER' },
  { to: '/skills', label: 'スキル', icon: 'skill' },
  { to: '/careers', label: '業務経歴', icon: 'career' },
  { to: '/certifications', label: '資格', icon: 'certification' },
  { to: '/evaluations/manager-result', label: '上長評価', icon: 'evaluation' },
  { to: '/evaluations/manager', label: '上長評価入力', icon: 'evaluation', role: 'OFFICER', scope: 'SUBORDINATES' },
  { to: '/approvals/talent', label: 'タレント承認', icon: 'approval', role: 'OFFICER', scope: 'SUBORDINATES' },
  { to: '/executive/evaluations', label: '最終承認', icon: 'approval', role: 'OFFICER', scope: 'ALL' },
  { to: '/master-requests', label: 'マスタ申請', icon: 'request' },
  { to: '/audit', label: '監査', icon: 'audit', role: 'ADMIN' },
]

const roleNames: Record<Role, string> = { GENERAL: '一般', OFFICER: '役職者', ADMIN: '管理者' }
const routeLabels: Array<{ matches: (path: string) => boolean; label: string }> = [
  { matches: (path) => path === '/', label: 'ダッシュボード' },
  { matches: (path) => path === '/employees/new', label: '社員登録' },
  { matches: (path) => /^\/employees\/[^/]+\/edit$/.test(path), label: '社員編集' },
  { matches: (path) => /^\/employees\/[^/]+$/.test(path), label: '社員詳細' },
  { matches: (path) => path === '/employees', label: '社員検索' },
  { matches: (path) => path === '/skills' || path === '/skills/edit', label: 'スキル' },
  { matches: (path) => path === '/careers' || path === '/careers/edit', label: '業務経歴' },
  { matches: (path) => path === '/certifications' || path === '/certifications/edit', label: '資格' },
  { matches: (path) => /^\/talent\/new\/[^/]+$/.test(path), label: 'タレント情報登録' },
  { matches: (path) => /^\/talent\/[^/]+\/history$/.test(path), label: '申請履歴' },
  { matches: (path) => /^\/approvals\/talent\/[^/]+$/.test(path), label: 'タレント承認詳細' },
  { matches: (path) => path === '/approvals/talent', label: 'タレント承認' },
  { matches: (path) => path === '/master-requests', label: 'マスタ申請' },
  { matches: (path) => path === '/evaluations/manager-result' || path === '/evaluations/self', label: '上長評価' },
  { matches: (path) => /^\/evaluations\/manager\/[^/]+$/.test(path), label: '上長評価入力' },
  { matches: (path) => path === '/evaluations/manager', label: '上長評価入力' },
  { matches: (path) => /^\/executive\/evaluations\/[^/]+$/.test(path), label: '最終承認詳細' },
  { matches: (path) => path === '/executive/evaluations', label: '最終承認' },
  { matches: (path) => path === '/analysis', label: '分析' },
  { matches: (path) => path === '/notifications', label: '通知' },
  { matches: (path) => path === '/masters', label: 'マスタ管理' },
  { matches: (path) => path === '/audit', label: '監査' },
  { matches: (path) => path === '/password/change', label: 'パスワード変更' },
  { matches: (path) => path === '/password/reset', label: 'パスワード再設定' },
  { matches: (path) => path === '/evaluations/history', label: '評価履歴比較' },
  { matches: (path) => path === '/organization', label: '組織・所属管理' },
  { matches: (path) => path === '/accounts', label: 'アカウント・権限管理' },
  { matches: (path) => path === '/forbidden', label: 'アクセス権限エラー' },
  { matches: (path) => path === '/error', label: 'エラー' },
]

function pageLabel(path: string) {
  return routeLabels.find((route) => route.matches(path))?.label ?? 'ページ'
}

export function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const unreadQuery = useQuery({
    queryKey: ['notifications', 'unread-count', user?.accountPublicId],
    queryFn: () => api<{ unreadCount: number }>('/api/v1/notifications/unread-count'),
    enabled: Boolean(user?.accountPublicId),
  })
  const unreadCount = unreadQuery.isError ? 0 : unreadQuery.data?.unreadCount ?? 0
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-logo brand-plate"><BrandLogo compact /></div>
        <nav aria-label="主要メニュー">
          {nav.filter((item) => {
            if (!item.role) return true
            if (item.to === '/employees' && user?.roles.includes('ADMIN')) return true
            return user?.roles.includes(item.role) && (!item.scope || user.scopes.includes(item.scope))
          }).map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
              <NavIcon name={item.icon} /><span className="nav-label">{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-user"><span className="status-dot" />{user?.displayName}<small>{user?.roles[0] ? roleNames[user.roles[0]] : ''}</small></div>
      </aside>
      <section className="main-column">
        <header className="topbar">
          <span className="breadcrumb">{pageLabel(location.pathname)}</span>
          <div className="top-actions"><LiveDateTime /><NavLink className="notification-link" to="/notifications" aria-label="通知を開く"><NavIcon name="notification" />{unreadCount > 0 && <NotificationBadge count={unreadCount} />}</NavLink><button className="ghost-button" onClick={() => void logout()}>ログアウト</button></div>
        </header>
        <main className="content"><Outlet /></main>
      </section>
    </div>
  )
}
