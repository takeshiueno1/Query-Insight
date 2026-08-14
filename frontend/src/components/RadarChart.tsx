import { useId } from 'react'
import type { Score } from '../types'

const size = 300
const center = size / 2
const radius = 94

function point(index: number, value: number, count: number, maxValue: number) {
  const angle = -Math.PI / 2 + index * Math.PI * 2 / count
  const scaled = radius * Math.max(0, Math.min(value, maxValue)) / maxValue
  return `${center + Math.cos(angle) * scaled},${center + Math.sin(angle) * scaled}`
}
export function RadarChart({ scores, maxValue = 5, title = '6軸評価レーダーチャート', valueLabel = '値', emptyLabel = '未評価' }: {
  scores: Score[]; maxValue?: number; title?: string; valueLabel?: string; emptyLabel?: string
}) {
  const titleId = useId()
  const descriptionId = useId()
  const normalized = scores.length >= 3 ? scores : Array.from({ length: 6 }, (_, index) => ({ axisCode: String(index), displayName: '未設定', level: 0 }))
  const polygon = normalized.map((score, index) => point(index, score.level, normalized.length, maxValue)).join(' ')
  return (
    <div className="radar-wrap">
      <svg className="radar" viewBox={`0 0 ${size} ${size}`} role="img" aria-label={title} aria-describedby={descriptionId}>
        <title id={titleId}>{title}</title>
        <desc id={descriptionId}>各項目を0から{maxValue}で表した図です。直後の表でも同じ値を確認できます。</desc>
        {[1, 2, 3, 4, 5].map((ring) => <polygon key={ring} points={normalized.map((_, index) => point(index, maxValue * ring / 5, normalized.length, maxValue)).join(' ')} className="radar-grid" />)}
        {normalized.map((_, index) => {
          const [x, y] = point(index, maxValue, normalized.length, maxValue).split(',')
          return <line key={index} x1={center} y1={center} x2={x} y2={y} className="radar-axis" />
        })}
        <polygon points={polygon} className="radar-value" />
        {normalized.map((score, index) => {
          const [x, y] = point(index, maxValue * 1.22, normalized.length, maxValue).split(',').map(Number)
          return <text key={score.axisCode} x={x} y={y} className="radar-label" textAnchor="middle">{score.displayName}</text>
        })}
      </svg>
      <table className="score-table">
        <thead><tr><th>項目</th><th>{valueLabel}</th></tr></thead>
        <tbody>{normalized.map((score) => <tr key={score.axisCode}><td>{score.displayName}</td><td>{score.level === 0 ? emptyLabel : score.level}</td></tr>)}</tbody>
      </table>
    </div>
  )
}
