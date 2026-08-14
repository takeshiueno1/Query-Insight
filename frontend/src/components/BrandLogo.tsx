import logo from '../assets/query-insight-logo.png'

export function BrandLogo({ compact = false }: { compact?: boolean }) {
  return <img className={compact ? 'brand-logo compact' : 'brand-logo'} src={logo} alt="QUERY INSIGHT" />
}
