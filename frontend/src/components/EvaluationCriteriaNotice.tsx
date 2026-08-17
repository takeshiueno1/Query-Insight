const criteria = [
  ['S', '卓越'],
  ['A', '高水準'],
  ['B', '期待以上'],
  ['C', '期待水準'],
  ['D', '要改善'],
  ['F', '大幅な改善が必要'],
] as const

export function EvaluationCriteriaNotice() {
  return (
    <section className="evaluation-criteria" aria-labelledby="evaluation-criteria-heading">
      <h2 id="evaluation-criteria-heading">評価基準と注意事項</h2>
      <dl className="rank-criteria">
        {criteria.map(([rank, meaning]) => <div key={rank}><dt>{rank}</dt><dd>{meaning}</dd></div>)}
      </dl>
      <ul>
        <li>スキル・得意分野・業務経歴・資格と、期間中の具体的な事実を根拠にしてください。</li>
        <li>年齢、性別、家族状況などの属性や印象だけで判断しないでください。</li>
        <li>コメントへ機密情報や不要な個人情報を書かないでください。</li>
        <li>AIは助言のみで、ランクや人事判断を決定しません。</li>
      </ul>
    </section>
  )
}
