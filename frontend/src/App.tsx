import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/AppLayout'
import { useAuth } from './features/auth/auth-context'
import { AuditPage } from './pages/AuditPage'
import { AiAnalysisPage } from './pages/AiAnalysisPage'
import { DashboardPage } from './pages/DashboardPage'
import { EmployeeDetailPage } from './pages/EmployeeDetailPage'
import { EmployeeFormPage } from './pages/EmployeeFormPage'
import { EmployeesPage } from './pages/EmployeesPage'
import { ErrorPage } from './pages/ErrorPage'
import { EvaluationPage } from './pages/EvaluationPage'
import { FeaturePage } from './pages/FeaturePage'
import { LoginPage } from './pages/LoginPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { TalentProfilePage } from './pages/TalentProfilePage'

function ProtectedLayout() {
  const { user, loading } = useAuth()
  if (loading) return <div className="boot-screen"><div className="spinner" /><span>QUERY INSIGHT</span></div>
  return user ? <AppLayout /> : <Navigate to="/login" replace />
}

export default function App() {
  return <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route element={<ProtectedLayout />}>
      <Route index element={<DashboardPage />} />
      <Route path="employees" element={<EmployeesPage />} />
      <Route path="employees/new" element={<EmployeeFormPage />} />
      <Route path="employees/:publicId/edit" element={<EmployeeFormPage />} />
      <Route path="employees/:publicId" element={<EmployeeDetailPage />} />
      <Route path="careers/edit" element={<TalentProfilePage />} />
      <Route path="skills/edit" element={<TalentProfilePage />} />
      <Route path="certifications/edit" element={<TalentProfilePage />} />
      <Route path="evaluations/self" element={<EvaluationPage />} />
      <Route path="evaluations/manager" element={<FeaturePage screenId="SCR-010" title="上長評価" description="自己評価と根拠を確認し、独立した上長評価を登録します" sections={['評価対象', '6軸評価', '差し戻し・提出']} />} />
      <Route path="evaluations/manage" element={<FeaturePage screenId="SCR-011" title="評価管理" description="評価期間、対象者、確定・訂正を管理します" sections={['評価期間', '進捗管理', '確定・訂正']} />} />
      <Route path="analysis" element={<AiAnalysisPage />} />
      <Route path="notifications" element={<NotificationsPage />} />
      <Route path="masters" element={<FeaturePage screenId="SCR-014" title="マスタ管理" description="評価基準版とスキル・資格マスタを履歴管理します" sections={['評価基準版', 'スキルマスタ', '資格マスタ']} />} />
      <Route path="audit" element={<AuditPage />} />
      <Route path="password/change" element={<FeaturePage screenId="SCR-016" title="パスワード変更" description="現在のパスワード確認後、15～128文字の新しいパスフレーズへ変更します" sections={['本人確認', 'ブロックリスト検査', '全セッション失効']} />} />
      <Route path="password/reset" element={<FeaturePage screenId="SCR-017" title="パスワード再設定" description="一回限り・30分有効のトークンで再設定します" sections={['本人確認', 'トークン検証', '再設定完了']} />} />
      <Route path="approvals" element={<FeaturePage screenId="SCR-018" title="承認ワーク一覧" description="経歴・スキル・資格の申請差分と根拠を確認します" sections={['承認待ち', '差分確認', '承認・差し戻し']} />} />
      <Route path="evaluations/history" element={<FeaturePage screenId="SCR-019" title="評価履歴比較" description="期間ごとの自己・上長・確定評価を混同せず比較します" sections={['期間選択', '6軸比較', '根拠スナップショット']} />} />
      <Route path="organization" element={<FeaturePage screenId="SCR-020" title="組織・所属管理" description="部署階層と所属・上長関係を有効期間付きで管理します" sections={['部署ツリー', '所属履歴', '異動登録']} />} />
      <Route path="accounts" element={<FeaturePage screenId="SCR-021" title="アカウント・権限管理" description="ロール、データスコープ、有効期間、付与理由を管理します" sections={['アカウント', '権限付与', '棚卸し']} />} />
      <Route path="error" element={<ErrorPage />} />
      <Route path="forbidden" element={<ErrorPage forbidden />} />
      <Route path="*" element={<ErrorPage />} />
    </Route>
  </Routes>
}
