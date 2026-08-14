import { useMutation } from '@tanstack/react-query'
import { ApiError, api } from '../lib/api'
import type { AiAnalysis } from '../types'

const priorityLabels = { HIGH: '高', MEDIUM: '中', LOW: '低' } as const

export function AnalysisPanel() {
  const analysis = useMutation({
    mutationFn: () => api<AiAnalysis>('/api/v1/ai-analyses', { method: 'POST' }),
    onError: () => undefined,
  })
  const errorDetail = analysis.error instanceof ApiError ? analysis.error.problem.detail : null
  const buttonLabel = analysis.isPending ? '分析中…' : analysis.isError ? '分析を再実行' : analysis.data ? '再分析する' : '分析を実行'

  return <section className="card dashboard-analysis" aria-labelledby="analysis-heading" aria-busy={analysis.isPending}>
    <div className="card-header analysis-header">
      <div>
        <h2 id="analysis-heading">育成助言</h2>
        <p>承認済み情報と本人へ公開済みの評価だけを使い、強みと次の行動を整理します。</p>
      </div>
      <button className="primary-button" disabled={analysis.isPending} onClick={() => analysis.mutate()}>{buttonLabel}</button>
    </div>
    <p className="analysis-boundary">分析は育成の参考情報です。ランク、昇進、配置などの人事判断を自動決定しません。</p>
    {analysis.isPending && <div className="inline-state" role="status" aria-live="polite"><span className="spinner" aria-hidden="true" />分析しています</div>}
    {analysis.isError && <div className="error-banner" role="alert">
      {errorDetail ?? '分析を完了できませんでした。時間をおいて再実行してください。'}
    </div>}
    {!analysis.data && !analysis.isPending && !analysis.isError && <div className="dashboard-empty" role="status" aria-label="分析結果の状態">
      分析を実行すると、育成のための助言を表示します。
    </div>}
    {analysis.data && <AnalysisResult analysis={analysis.data} />}
  </section>
}

function AnalysisResult({ analysis }: { analysis: AiAnalysis }) {
  const mode = analysis.analysisMode === 'PROTOTYPE' ? 'プロトタイプ分析' : 'ローカルAI分析'
  return <div className="dashboard-analysis-results">
    <div className="analysis-result-meta">
      <span className={analysis.analysisMode === 'PROTOTYPE' ? 'status warning' : 'status success'}>{mode}</span>
      <time dateTime={analysis.generatedAt}>{new Date(analysis.generatedAt).toLocaleString('ja-JP')}</time>
    </div>
    <p className="analysis-summary">{analysis.summary}</p>
    <div className="analysis-grid">
      <InsightList title="強み" items={analysis.strengths} />
      <InsightList title="成長領域" items={analysis.growthAreas} />
    </div>
    <div className="analysis-actions">
      <h3>推奨アクション</h3>
      <ul>{analysis.recommendedActions.map((item, index) => <li key={`${item.action}-${index}`}>
        <strong>{item.action}</strong><span>優先度: {priorityLabels[item.priority]}</span>
      </li>)}</ul>
    </div>
  </div>
}

function InsightList({ title, items }: { title: string; items: Array<{ title: string; evidence: string }> }) {
  return <section className="insight-list"><h3>{title}</h3>{items.length === 0
    ? <p>該当する助言はありません。</p>
    : items.map((item, index) => <article key={`${item.title}-${index}`}><strong>{item.title}</strong><p>{item.evidence}</p></article>)}</section>
}
