export function BrandLogo({ compact = false }: { compact?: boolean }) {
  return (
    <svg className={compact ? 'brand-logo compact' : 'brand-logo'} viewBox="0 0 360 88" role="img" aria-label="QUERY INSIGHT">
      <defs><linearGradient id="brand-gold" x1="0" x2="1"><stop stopColor="#ffe45c"/><stop offset="1" stopColor="#f6bd00"/></linearGradient></defs>
      <g fill="none" stroke="url(#brand-gold)" strokeWidth="9"><circle cx="44" cy="40" r="27"/><path d="M64 60 82 78" strokeLinecap="round"/></g>
      <path d="M45 23c-12 0-20 7-20 18s8 19 20 19c5 0 9-1 12-4l8 8 7-7-8-8c2-3 3-6 3-9 0-10-9-17-22-17Zm0 10c6 0 10 3 10 8s-4 9-10 9-10-4-10-9 4-8 10-8Z" fill="#f7f9fb"/>
      {!compact && <><text x="92" y="48" fill="#f7f9fb" fontSize="31" fontWeight="800" letterSpacing="3">QUERY</text><text x="218" y="48" fill="url(#brand-gold)" fontSize="24" fontWeight="800" letterSpacing="2">INSIGHT</text><path d="M94 62h230" stroke="#ffd400" strokeWidth="2" opacity=".7"/></>}
    </svg>
  )
}
