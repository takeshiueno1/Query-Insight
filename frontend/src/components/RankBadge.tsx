import type { EvaluationRank } from '../types'

export function RankBadge({ rank, label }: { rank: EvaluationRank; label: string }) {
  return <span className={`rank-badge rank-${rank.toLowerCase()}`} aria-label={`${label} ${rank}`}>
    <span aria-hidden="true">{rank}</span>
  </span>
}
