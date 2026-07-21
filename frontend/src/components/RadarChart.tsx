import type { Score } from '../types'

const size = 300
const center = size / 2
const radius = 94

function point(index: number, value: number) {
  const angle = -Math.PI / 2 + index * Math.PI / 3
  const scaled = radius * value / 5
  return `${center + Math.cos(angle) * scaled},${center + Math.sin(angle) * scaled}`
}
export function RadarChart({ scores }: { scores: Score[] }) {
  const normalized = scores.length === 6 ? scores : Array.from({ length: 6 }, (_, index) => ({ axisCode: String(index), displayName: '未設定', level: 0 }))
  const polygon = normalized.map((score, index) => point(index, score.level)).join(' ')
  return (
    <div className="radar-wrap">
      <svg className="radar" viewBox={`0 0 ${size} ${size}`} role="img" aria-labelledby="radar-title radar-desc">
        <title id="radar-title">6軸評価レーダーチャート</title>
        <desc id="radar-desc">各評価軸を0から5で表した図です。直後の表でも同じ値を確認できます。</desc>
        {[1, 2, 3, 4, 5].map((level) => <polygon key={level} points={normalized.map((_, index) => point(index, level)).join(' ')} className="radar-grid" />)}
        {normalized.map((_, index) => <line key={index} x1={center} y1={center} x2={point(index, 5).split(',')[0]} y2={point(index, 5).split(',')[1]} className="radar-axis" />)}
        <polygon points={polygon} className="radar-value" />
        {normalized.map((score, index) => {
          const [x, y] = point(index, 6.2).split(',').map(Number)
          return <text key={score.axisCode} x={x} y={y} className="radar-label" textAnchor="middle">{score.displayName}</text>
        })}
      </svg>
      <table className="score-table">
        <thead><tr><th>評価軸</th><th>値</th></tr></thead>
        <tbody>{normalized.map((score) => <tr key={score.axisCode}><td>{score.displayName}</td><td>{score.level || '未評価'}</td></tr>)}</tbody>
      </table>
    </div>
  )
}
