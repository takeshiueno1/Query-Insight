export function FeaturePage({ eyebrow, title, description, sections }: { eyebrow: string; title: string; description: string; sections: string[] }) {
  return <><div className="page-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div></div><section className="card"><div className="feature-sections">{sections.map((section) => <article key={section}><span>○</span><div><h2>{section}</h2><p>登録済みデータがある場合に表示されます。権限範囲外の情報は取得しません。</p></div></article>)}</div></section></>
}
