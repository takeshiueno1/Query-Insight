# ブランド・ナビゲーション・サンプル社員拡張 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 黒い虫眼鏡ロゴ、幾何学背景、右上の通知ベルと日時、用途別SVGアイコン、5名のローカル社員データを実装する。

**Architecture:** UIは既存React構造へ依存なしの小さなSVG/日時コンポーネントを追加し、AppLayoutとCSSから利用する。データは既存のローカル初期化処理を55名へ決定的に拡張し、既存のPostgreSQL互換SQLと冪等条件を維持する。

**Tech Stack:** React 19.2、TypeScript 6、Vitest、CSS、Java 21、Spring Boot 4.1、Spring JDBC、PostgreSQL 18.4/H2 PostgreSQL mode

## Global Constraints

- 外部アイコン依存を追加しない。
- 通知API、認証・認可、DBスキーマを変更しない。
- サンプル社員は `local` プロファイルだけで生成し、実在情報を使わない。
- 通知キャッシュは `accountPublicId` ごとに分離し、失敗時は件数を非表示にする。
- 390px幅で主要操作が重ならないこと。

---

### Task 1: ブランドロゴと用途別アイコン

**Files:**
- Create: `frontend/src/components/NavIcon.tsx`
- Modify: `frontend/src/components/BrandLogo.tsx`
- Test: `frontend/src/pages/LoginPage.test.tsx`
- Test: `frontend/src/components/AppLayout.test.tsx`

**Interfaces:**
- Produces: `BrandLogo({ compact?: boolean })`、`NavIcon({ name: NavIconName })`

- [ ] **Step 1: Write failing tests** — ロゴがアクセシブルなSVGで、資格メニューに認定書アイコンがあることを検証する。
- [ ] **Step 2: Run tests to verify RED** — `npm test -- LoginPage.test.tsx AppLayout.test.tsx`
- [ ] **Step 3: Implement minimal components** — SVGロゴと項目別SVGパスを実装する。
- [ ] **Step 4: Run tests to verify GREEN** — 同じfocused testを実行する。

### Task 2: 通知ベルと現在日時

**Files:**
- Create: `frontend/src/components/LiveDateTime.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Test: `frontend/src/components/AppLayout.test.tsx`

**Interfaces:**
- Consumes: `NotificationBadge`、利用者別未読query。
- Produces: `LiveDateTime({ now?: () => Date })`、右上の `/notifications` リンク。

- [ ] **Step 1: Write failing tests** — 左メニューに通知がなく、右上ベルにバッジがあり、日時が毎分更新されることをfake timerで検証する。
- [ ] **Step 2: Run test to verify RED** — `npm test -- AppLayout.test.tsx`
- [ ] **Step 3: Implement minimal layout** — 通知リンクをtopbarへ集約し、`time` とinterval cleanupを実装する。
- [ ] **Step 4: Run test to verify GREEN** — focused testを実行する。

### Task 3: ガラス調背景とレスポンシブ表示

**Files:**
- Modify: `frontend/src/styles.css`
- Test: `frontend/src/pages/LoginPage.test.tsx`
- Test: `frontend/src/components/AppLayout.test.tsx`

**Interfaces:**
- Consumes: `.brand-mark`、`.nav-icon`、`.notification-link`、`.live-datetime`。

- [ ] **Step 1: Add structural assertions** — ロゴプレート、装飾レイヤー、topbar操作群のクラスを検証する。
- [ ] **Step 2: Run focused tests to verify RED** — `npm test -- LoginPage.test.tsx AppLayout.test.tsx`
- [ ] **Step 3: Implement CSS** — 黒プレート、グラデーション、円・六角形・斜線、390px配置、reduced-motionを追加する。
- [ ] **Step 4: Run focused tests and build** — focused testと`npm run build`を実行する。

### Task 4: 5名のローカル社員データ

**Files:**
- Modify: `backend/src/main/java/com/query/insight/config/LocalRealisticDataInitializer.java`
- Modify: `backend/src/test/java/com/query/insight/auth/AuthServiceIntegrationTests.java`

**Interfaces:**
- Produces: `QI0051`〜`QI0055`、`qi0051@query.local`〜`qi0055@query.local`、QI0051直属の4名。

- [ ] **Step 1: Write failing integration tests** — 合計56件、追加5名のアカウント、QI0051の役職者/SUBORDINATES、QI0052〜55の一般/SELF、直属関係と保有情報を検証する。
- [ ] **Step 2: Run test to verify RED** — `backend\\mvnw.cmd -Dtest=AuthServiceIntegrationTests test`
- [ ] **Step 3: Extend deterministic seed** — 名前リストと生成範囲、manager規則、manager集合を55名へ拡張する。
- [ ] **Step 4: Run test to verify GREEN** — focused integration testを実行し、二重実行件数も確認する。

### Task 5: 文書同期と全体検証

**Files:**
- Modify: `README.md`
- Modify: `docs/08-realistic-sample-data.md`
- Modify: `docs/06-traceability.md`（該当要件行がある場合のみ）

**Interfaces:**
- Consumes: 実装後の実測件数とログイン規則。

- [ ] **Step 1: Update exact documented counts** — 55名＋テスト1名、合計56と追加ログイン範囲を記載する。
- [ ] **Step 2: Run full verification** — Backend全test、Frontend全test/lint/build、`docker compose config`、`git diff --check`を実行する。
- [ ] **Step 3: Runtime verification** — Docker利用可能ならPostgreSQL Composeとブラウザ390pxを確認し、利用不能ならH2フォールバックと環境障害を分離して記録する。
- [ ] **Step 4: Review and commit scoped files** — 秘密情報、差分、未追跡ファイルを確認し、日本語commit、pushなしで完了する。

