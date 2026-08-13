import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'
import type { DataScope, Role } from '../types'

type NavItem = { to: string; label: string; icon: string; role?: Role; scope?: DataScope }
const nav: NavItem[] = [
  { to: '/', label: 'ダッシュボード', icon: '⌂' },
  { to: '/employees', label: '社員検索', icon: '⌕', role: 'OFFICER' },
  { to: '/skills/edit', label: 'スキル', icon: '◇' },
  { to: '/evaluations/self', label: '評価', icon: '✓' },
  { to: '/evaluations/manager', label: '上長評価', icon: '◎', role: 'OFFICER', scope: 'SUBORDINATES' },
  { to: '/approvals/talent', label: 'タレント承認', icon: '▣', role: 'OFFICER', scope: 'SUBORDINATES' },
  { to: '/executive/evaluations', label: '最終承認', icon: '◆', role: 'OFFICER', scope: 'ALL' },
  { to: '/analysis', label: 'AI分析', icon: '✦' },
  { to: '/notifications', label: '通知', icon: '●' },
  { to: '/master-requests', label: 'マスタ申請', icon: '＋' },
  { to: '/audit', label: '監査', icon: '▤', role: 'ADMIN' },
]

const roleNames: Record<Role, string> = { GENERAL: '一般', OFFICER: '役職者', ADMIN: '管理者' }

export function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">QUERY INSIGHT</div>
        <nav aria-label="主要メニュー">
          {nav.filter((item) => {
            if (!item.role) return true
            if (item.to === '/employees' && user?.roles.includes('ADMIN')) return true
            return user?.roles.includes(item.role) && (!item.scope || user.scopes.includes(item.scope))
          }).map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
              <span aria-hidden="true">{item.icon}</span>{item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-user"><span className="status-dot" />{user?.displayName}<small>{user?.roles[0] ? roleNames[user.roles[0]] : ''}</small></div>
      </aside>
      <section className="main-column">
        <header className="topbar">
          <span className="breadcrumb">QUERY INSIGHT / {location.pathname === '/' ? 'DASHBOARD' : location.pathname.toUpperCase()}</span>
          <div className="top-actions"><NavLink to="/notifications" aria-label="通知">●</NavLink><button className="ghost-button" onClick={() => void logout()}>ログアウト</button></div>
        </header>
        <main className="content"><Outlet /></main>
      </section>
    </div>
  )
}
