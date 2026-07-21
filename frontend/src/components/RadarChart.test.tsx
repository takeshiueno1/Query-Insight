import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RadarChart } from './RadarChart'

describe('RadarChart', () => {
  it('図と同じ値をアクセシブルな表へ表示する', () => {
    const scores = ['技術力','設計力','業務理解','説明力','推進力','改善力'].map((displayName, index) => ({ axisCode: String(index), displayName, level: index % 5 + 1 }))
    render(<RadarChart scores={scores} />)
    expect(screen.getByRole('img', { name: /^6軸評価レーダーチャート/ })).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('技術力')
    expect(screen.getByRole('table')).toHaveTextContent('改善力')
  })
})
