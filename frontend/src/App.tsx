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
import { ManagerEvaluationsPage } from './pages/ManagerEvaluationsPage'
import { ManagerEvaluationDetailPage } from './pages/ManagerEvaluationDetailPage'
import { FinalManagerEvaluationPage } from './pages/FinalManagerEvaluationPage'
import { ExecutiveDashboardPage } from './pages/ExecutiveDashboardPage'
import { ExecutiveEvaluationDetailPage } from './pages/ExecutiveEvaluationDetailPage'
import { FeaturePage } from './pages/FeaturePage'
import { LoginPage } from './pages/LoginPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { TalentSubmissionFormPage } from './pages/TalentSubmissionFormPage'
import { TalentSubmissionHistoryPage } from './pages/TalentSubmissionHistoryPage'
import { ManagerTalentApprovalsPage } from './pages/ManagerTalentApprovalsPage'
import { ManagerTalentApprovalDetailPage } from './pages/ManagerTalentApprovalDetailPage'
import { MasterRequestsPage } from './pages/MasterRequestsPage'
import { SkillsPage } from './pages/SkillsPage'
import { CareersPage } from './pages/CareersPage'
import { CertificationsPage } from './pages/CertificationsPage'

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
      <Route path="skills" element={<SkillsPage />} />
      <Route path="careers" element={<CareersPage />} />
      <Route path="certifications" element={<CertificationsPage />} />
      <Route path="skills/edit" element={<Navigate to="/skills" replace />} />
      <Route path="careers/edit" element={<Navigate to="/careers" replace />} />
      <Route path="certifications/edit" element={<Navigate to="/certifications" replace />} />
      <Route path="talent/new/:type" element={<TalentSubmissionFormPage />} />
      <Route path="talent/:type/:publicId/edit" element={<TalentSubmissionFormPage />} />
      <Route path="talent/:logicalPublicId/history" element={<TalentSubmissionHistoryPage />} />
      <Route path="approvals/talent" element={<ManagerTalentApprovalsPage />} />
      <Route path="approvals/talent/:publicId" element={<ManagerTalentApprovalDetailPage />} />
      <Route path="master-requests" element={<MasterRequestsPage />} />
      <Route path="evaluations/self" element={<Navigate to="/evaluations/manager-result" replace />} />
      <Route path="evaluations/manager-result" element={<FinalManagerEvaluationPage />} />
      <Route path="evaluations/manager" element={<ManagerEvaluationsPage />} />
      <Route path="evaluations/manager/:publicId" element={<ManagerEvaluationDetailPage />} />
      <Route path="executive/evaluations" element={<ExecutiveDashboardPage />} />
      <Route path="executive/evaluations/:publicId" element={<ExecutiveEvaluationDetailPage />} />
      <Route path="analysis" element={<AiAnalysisPage />} />
      <Route path="notifications" element={<NotificationsPage />} />
      <Route path="masters" element={<FeaturePage screenId="SCR-014" title="マスタ管理" description="評価基準版とスキル・資格マスタを履歴管理します" sections={['評価基準版', 'スキルマスタ', '資格マスタ']} />} />
      <Route path="audit" element={<AuditPage />} />
      <Route path="password/change" element={<FeaturePage screenId="SCR-016" title="パスワード変更" description="現在のパスワード確認後、半角英数字8～128文字（英字・数字を各1文字以上）の新しいパスワードへ変更します" sections={['本人確認', '入力条件検査', '全セッション失効']} />} />
      <Route path="password/reset" element={<FeaturePage screenId="SCR-017" title="パスワード再設定" description="一回限り・30分有効のトークンで再設定します" sections={['本人確認', 'トークン検証', '再設定完了']} />} />
      <Route path="approvals" element={<Navigate to="/approvals/talent" replace />} />
      <Route path="evaluations/history" element={<FeaturePage screenId="SCR-019" title="評価履歴比較" description="期間ごとの自己・上長・確定評価を混同せず比較します" sections={['期間選択', '6軸比較', '根拠スナップショット']} />} />
      <Route path="organization" element={<FeaturePage screenId="SCR-020" title="組織・所属管理" description="部署階層と所属・上長関係を有効期間付きで管理します" sections={['部署ツリー', '所属履歴', '異動登録']} />} />
      <Route path="accounts" element={<FeaturePage screenId="SCR-021" title="アカウント・権限管理" description="ロール、データスコープ、有効期間、付与理由を管理します" sections={['アカウント', '権限付与', '棚卸し']} />} />
      <Route path="error" element={<ErrorPage />} />
      <Route path="forbidden" element={<ErrorPage forbidden />} />
      <Route path="*" element={<ErrorPage />} />
    </Route>
  </Routes>
}
