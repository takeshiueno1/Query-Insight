# ドキュメントガイド

`docs/` は、Query Insightの一次資料、解析結果、実装との対応関係を管理します。一次資料は変更せず `source/original/` に保管し、解釈・正規化した内容を番号付き文書に記録します。

## 文書一覧

| 文書 | 役割 | 状態 |
| --- | --- | --- |
| [source/project-context.md](source/project-context.md) | 一次資料台帳、前提、用語 | 解析済み |
| [01-product-overview.md](01-product-overview.md) | 課題、利用者、価値、スコープ | 解析済み |
| [02-requirements.md](02-requirements.md) | 機能・データ・業務要件 | 解析済み |
| [03-use-cases.md](03-use-cases.md) | 主要操作フローと例外 | 解析済み |
| [04-non-functional-requirements.md](04-non-functional-requirements.md) | 性能、セキュリティ、運用要件 | 解析済み（一部数値未決） |
| [05-analysis-report.md](05-analysis-report.md) | 資料横断分析、矛盾、実装判断 | 作成済み |
| [06-traceability.md](06-traceability.md) | 要件・画面・API・実装状況 | 作成済み |
| [07-free-deployment.md](07-free-deployment.md) | Render・Neon無料公開版の構成、設定、検証 | 作成済み |
| [08-realistic-sample-data.md](08-realistic-sample-data.md) | 50名分の架空社員・評価・タレントプロフィールとAI送信範囲 | 作成済み |
| [09-security-measures.md](09-security-measures.md) | 無料で実装した多層防御、設定値、運用上の制約 | 作成済み |
| [99-open-questions.md](99-open-questions.md) | 意思決定が必要な論点 | 更新中 |

## 一次資料

提供されたPDF 4件（計68ページ）とExcel 4件（計62シート）を [source/original](source/original/) に格納しています。ファイル名、サイズ、SHA-256は [project-context.md](source/project-context.md) に記録しています。

## 更新ルール

- 確認済み事実、実装上の判断、未決事項を区別する。
- 資料間の矛盾は `05-analysis-report.md` と `99-open-questions.md` に記録する。
- 要件変更時は `06-traceability.md` と関連テストも更新する。
- 一次資料は上書きせず、改訂版を別ファイルとして追加する。
- 実在する個人情報・認証情報をサンプルデータへ転記しない。
