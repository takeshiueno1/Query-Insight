import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { RadarChart } from './RadarChart'

describe('RadarChart', () => {
  afterEach(cleanup)

  it('図と同じ値をアクセシブルな表へ表示する', () => {
    const scores = ['技術力','設計力','業務理解','説明力','推進力','改善力'].map((displayName, index) => ({ axisCode: String(index), displayName, level: index % 5 + 1 }))
    render(<RadarChart scores={scores} />)
    expect(screen.getByRole('img', { name: /^6軸評価レーダーチャート/ })).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('技術力')
    expect(screen.getByRole('table')).toHaveTextContent('改善力')
  })

  it('4分野の100点尺度を横幅に収まる図と表で表示する', () => {
    const scores = [
      { axisCode: 'SKILL', displayName: 'スキル', level: 80 },
      { axisCode: 'KNOWLEDGE', displayName: '得意分野', level: 0 },
      { axisCode: 'CAREER', displayName: '業務経歴', level: 65.5 },
      { axisCode: 'CERTIFICATION', displayName: '資格', level: 40 },
    ]
    render(<RadarChart scores={scores} maxValue={100} title="承認済み情報の能力バランス" valueLabel="点数" emptyLabel="未登録" />)

    expect(screen.getByRole('img', { name: '承認済み情報の能力バランス' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('得意分野未登録')
    expect(screen.getByRole('table')).toHaveTextContent('業務経歴65.5')
  })
})
