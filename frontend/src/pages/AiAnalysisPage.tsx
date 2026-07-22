import { useMutation } from '@tanstack/react-query'
import { ApiError, api } from '../lib/api'
import type { AiAnalysis } from '../types'

export function AiAnalysisPage() {
  const analysis = useMutation({ mutationFn: () => api<AiAnalysis>('/api/v1/ai-analyses', { method: 'POST' }) })
  const problem = analysis.error instanceof ApiError ? analysis.error.problem : null

  return <>
    <div className="page-heading"><div><span className="eyebrow">SCR-012</span><h1>AI能力分析</h1><p>評価、スキル、専門知識、業務経験から、育成のための示唆を作成します</p></div></div>
    <section className="card analysis-consent">
      <div><h2>ローカルAIへ渡す情報</h2><p>評価期間・能力軸・根拠、スキル、専門知識、直近の業務経験、確認済み資格を、同じPC内のOllamaへ渡します。外部AIサービスへは送信しません。また、氏名、社員番号、メールアドレス、部署名、公開IDはAI入力に含めません。</p></div>
      <button className="primary-button" disabled={analysis.isPending} onClick={() => analysis.mutate()}>{analysis.isPending ? '分析中…' : 'AI分析を実行'}</button>
    </section>
    {problem && <div className="error-banner" role="alert">{problem.detail}（{problem.code}）</div>}
    {!analysis.data && !problem && <section className="card"><h2>分析結果</h2><p>実行すると、強み、成長領域、推奨アクションがここに表示されます。AIの出力は参考情報であり、人事判断を自動決定するものではありません。</p></section>}
    {analysis.data && <AnalysisResult analysis={analysis.data} />}
  </>
}

function AnalysisResult({ analysis }: { analysis: AiAnalysis }) {
  return <div className="analysis-results">
    <section className="card"><div className="card-header"><div><h2>分析サマリー</h2><p>{analysis.periodName}</p></div><span className="updated">{analysis.model} / {new Date(analysis.generatedAt).toLocaleString('ja-JP')}</span></div><p className="analysis-summary">{analysis.summary}</p></section>
    <div className="analysis-grid">
      <InsightCard title="強み" items={analysis.strengths} />
      <InsightCard title="成長領域" items={analysis.growthAreas} />
    </div>
    <section className="card"><h2>推奨アクション</h2><ul className="task-list">{analysis.recommendedActions.map((item, index) => <li key={`${item.action}-${index}`}><span className="task-icon">{index + 1}</span><div><strong>{item.action}</strong><small>優先度: {item.priority}</small></div></li>)}</ul></section>
  </div>
}

function InsightCard({ title, items }: { title: string; items: Array<{ title: string; evidence: string }> }) {
  return <section className="card"><h2>{title}</h2><div className="insight-list">{items.map((item, index) => <article key={`${item.title}-${index}`}><strong>{item.title}</strong><p>{item.evidence}</p></article>)}</div></section>
}
