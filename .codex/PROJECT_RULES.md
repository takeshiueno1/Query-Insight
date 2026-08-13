# Query Insight固有ルール

## プロジェクト概要と技術構成

- 社員のスキル、専門知識、業務経歴、資格、自己評価、上長評価、経営者承認を根拠と履歴付きで管理する社内Webアプリケーションである。
- `backend/` はJava 21、Spring Boot 4.1、Spring Security、OAuth2 Resource Server、Spring JDBC、Flywayを使用する。
- `frontend/` はReact 19.2、TypeScript 6、Vite 8、TanStack Query、React Hook Form、Zodを使用する。
- `compose.yml` はPostgreSQL 18.4、Ollama 0.32.1、ClamAV 1.4.3、backend、frontendで構成し、UIは `http://localhost:8088` を使用する。
- Render/Neon向け無料公開版の設定は存在するが、外部リソース作成、公開、デプロイ、秘密情報登録は事前承認なしに行わない。

## 優先して参照する実在資料

1. `docs/source/original/` の受領一次資料と `docs/source/project-context.md`
2. `docs/06-traceability.md` の要件・画面・実装対応
3. `docs/99-open-questions.md` の未決事項
4. `README.md` と `docs/README.md`
5. `docs/02-requirements.md`、`docs/04-non-functional-requirements.md`、`docs/05-analysis-report.md`
6. `docs/09-security-measures.md`
7. 変更対象の実コード、テスト、Flyway migration、CI、Compose定義

- 実在しない構成や実装済み機能を文書へ追加しない。画面骨格、DB基盤、API接続済み、実動作確認済みを区別する。
- 機能完了を判断する前に `docs/06-traceability.md` を確認し、要件とコードの差分があれば同期する。
- 未決事項を推測で固定ロジックへ埋め込まず、設定、マスターデータ、feature flagまたは確認事項として扱う。

## 業務・セキュリティ上の不変条件

- 認証は短寿命JWT、Refresh Tokenローテーション、再利用検知、送信元単位のレート制限を維持し、アカウントロックは使用しない。パスワードは英字・数字を各1文字以上含む半角英数字8〜128文字とする。認可は一般・役職者・管理者の3権限だけでなく本人・上長割当・直属部下・全社scopeをAPIで検証する。
- 評価、タレント申請、添付、承認、差戻し、再申請、再オープンは状態遷移、版、楽観ロック、監査ログ、過去履歴を保持する。
- `OFFICER` の全社操作は `scope_type='ALL'` を必要とし、初回競合を含む版競合は409として扱う。`ADMIN`だけを理由に他社員のタレント内容を公開しない。
- 根拠資料は任意とする。添付時はPDF/JPEG/PNGの実体、件数・サイズ、アクセスscope、ClamAV検査を確認し、検査成功前に正式公開しない。
- AIは助言用途で、人事判断を自動決定しない。既定はローカルOllama/Qwen3とし、氏名、メール、社員番号、部署名、公開IDを入力へ含めない。
- 実在社員情報を外部AIへ送信しない。外部AI、外部API、Render/Neonへ機密データを送る変更は事前承認を必要とする。
- `.env`、JWT秘密鍵、認証情報、実在社員情報をGit、ログ、マスター、評価データへ保存しない。
- 公開版ではOllamaを無効にし、実在社員の個人情報・評価情報を登録しない。

## 固有の検証コマンド

```powershell
# Backend
Set-Location backend
.\mvnw.cmd test

# Frontend
Set-Location ..\frontend
npm ci
npm run lint
npm test
npm run build

# Compose定義
Set-Location ..
docker compose config
```

- 実動作確認が必要な場合は `docker info`、`docker compose ps`、backend/frontendログ、HTTPの順に確認する。
- Composeを再構築した場合はPostgreSQLとbackendのhealthを待ち、`http://localhost:8088` でログイン、対象API、主要画面を確認する。HTTPエラーはレスポンスの `traceId` とbackendログを照合する。
- 無料公開版相当のローカル検証は `docker compose -f compose.yml -f compose.free.yml up --build db free-app` と `http://localhost:8090` を使用するが、外部デプロイは別の承認境界とする。
