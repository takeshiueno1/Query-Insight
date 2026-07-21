import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../features/auth/auth-context'

const nav = [
  { to: '/', label: 'ダッシュボード', icon: '⌂' },
  { to: '/employees', label: '社員検索', icon: '⌕', roles: ['MANAGER', 'SALES', 'HR', 'SYSTEM_ADMIN', 'AUDITOR'] },
  { to: '/skills/edit', label: 'スキル', icon: '◇' },
  { to: '/evaluations/self', label: '評価', icon: '✓' },
  { to: '/analysis', label: 'AI分析', icon: '✦' },
  { to: '/notifications', label: '通知', icon: '●' },
  { to: '/audit', label: '監査', icon: '▤', roles: ['AUDITOR', 'HR', 'SYSTEM_ADMIN'] },
]

export function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">QUERY INSIGHT</div>
        <nav aria-label="主要メニュー">
          {nav.filter((item) => !item.roles || item.roles.some((role) => user?.roles.includes(role))).map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
              <span aria-hidden="true">{item.icon}</span>{item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-user"><span className="status-dot" />{user?.displayName}<small>{user?.roles[0]}</small></div>
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
