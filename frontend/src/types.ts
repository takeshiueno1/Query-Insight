export type Role = 'GENERAL' | 'OFFICER' | 'ADMIN'
export type DataScope = 'SELF' | 'SUBORDINATES' | 'ALL'

export type User = {
  accountPublicId: string
  employeePublicId: string
  displayName: string
  roles: Role[]
  scopes: DataScope[]
}

export type Problem = {
  title: string
  detail: string
  status: number
  code: string
  traceId?: string
  fieldErrors?: Array<{ field: string; code: string; message: string }>
}

export type Score = { axisCode: string; displayName: string; level: number }

export type EvaluationRank = 'S' | 'A' | 'B' | 'C' | 'D' | 'F'

export const evaluationStatusLabels: Record<string, string> = {
  DRAFT: '下書き',
  SELF_IN_PROGRESS: '本人入力中',
  SELF_SUBMITTED: '上長評価待ち',
  SELF_RETURNED: '上長評価の再開待ち',
  MANAGER_IN_PROGRESS: '上長入力中',
  MANAGER_RETURNED: '最終承認者から差戻し',
  EXECUTIVE_REVIEW: '最終承認待ち',
  FINALIZED: '確定済み',
}

export type ProfileStatus = {
  publicId: string
  skillScore: number
  knowledgeScore: number
  careerScore: number
  certificationScore: number
  totalScore: number
  grade: EvaluationRank
  missingCategories: Array<'SKILL' | 'KNOWLEDGE' | 'CAREER' | 'CERTIFICATION'>
  formulaVersion: string
  calculatedAt: string
  editable: false
}

export type FinalManagerEvaluation = {
  status: 'FINALIZED'
  finalRank: EvaluationRank
  summary: string | null
  details: Array<{ axisCode: string; displayName: string; managerRank: EvaluationRank; comment: string | null }>
  finalizedAt: string
}

export type Dashboard = {
  profile: {
    employeeNo: string
    name: string
    department: string | null
    positionName: string | null
    updatedAt: string
  }
  profileStatus: ProfileStatus
  finalManagerEvaluation: FinalManagerEvaluation | null
  unreadNotifications: number
}

export type EmployeeSummary = {
  publicId: string
  employeeNo: string
  name: string
  email: string
  departmentCode: string | null
  departmentName: string | null
  positionName: string | null
  employmentStatus: string
  version: number
  updatedAt: string
}

export type EmployeePage = {
  content: EmployeeSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type EmployeeDetail = {
  publicId: string
  employeeNo: string
  lastName: string
  firstName: string
  email: string
  departmentCode: string | null
  departmentName: string | null
  managerPublicId: string | null
  managerName: string | null
  positionName: string | null
  employmentStatus: string
  hireDate: string | null
  retirementDate: string | null
  version: number
  updatedAt: string
}

export type SelfEvaluation = {
  publicId: string
  periodName: string
  status: string
  version: number
  details: Array<{
    axisCode: string
    displayName: string
    description: string
    level: number | null
    evidence: string | null
  }>
}

export type ManagerEvaluationListItem = {
  publicId: string
  employeeName: string
  departmentName: string | null
  periodName: string
  status: string
  version: number
  late: boolean
}

export type EvaluationComparisonDetail = {
  axisCode: string
  displayName: string
  description: string
  managerRank: EvaluationRank | null
  managerComment: string | null
}

export type ManagerEvaluation = {
  publicId: string
  employeePublicId: string
  employeeName: string
  departmentName: string | null
  periodName: string
  status: string
  targetVersion: number
  late: boolean
  summary: string | null
  grade: EvaluationRank | null
  details: EvaluationComparisonDetail[]
}

export type ExecutiveDashboard = {
  counts: { total: number; pending: number; finalized: number; overdue: number }
  items: Array<{ publicId: string; employeeName: string; departmentName: string | null; status: string; version: number; finalGrade: EvaluationRank | null; managerGrade: EvaluationRank | null; periodName: string; late: boolean }>
  distributions: Array<{ departmentName: string; grade: string; employeeCount: number }>
}

export type ExecutiveEvaluation = {
  publicId: string
  employeeName: string
  departmentName: string | null
  periodName: string
  status: string
  targetVersion: number
  late: boolean
  summary: string | null
  grade: EvaluationRank | null
  finalGrade: EvaluationRank | null
  details: EvaluationComparisonDetail[]
  events: Array<{ action: string; fromStatus: string; toStatus: string; reason: string | null; comment: string | null; late: boolean; deadlineType: 'SELF' | 'MANAGER'; occurredAt: string }>
}

export type FinalEvaluationResult = {
  status: string
  finalScore: number | null
  finalGrade: string | null
  summary: string | null
  details: Array<{ axisCode: string; displayName: string; selfLevel: number; managerLevel: number; comment: string | null }>
}

export type AiAnalysis = {
  publicId: string
  periodName: string
  summary: string
  strengths: Array<{ title: string; evidence: string }>
  growthAreas: Array<{ title: string; evidence: string }>
  recommendedActions: Array<{ action: string; priority: 'HIGH' | 'MEDIUM' | 'LOW' }>
  model: string
  generatedAt: string
  analysisMode: 'AI' | 'PROTOTYPE'
}

export type TalentProfile = {
  skills: Array<{
    code: string
    name: string
    category: string
    level: number
    yearsExperience: number
    lastUsedOn: string
    evidence: string
  }>
  knowledge: Array<{
    code: string
    name: string
    category: string
    level: number
    evidence: string
  }>
  careers: Array<{
    projectName: string
    industry: string
    roleName: string
    startDate: string
    endDate: string | null
    summary: string
    achievements: string
    technologies: string
  }>
  certifications: Array<{
    code: string
    name: string
    issuer: string
    acquiredOn: string
    expiresOn: string | null
    verificationStatus: string
  }>
}

export type TalentSubmissionStatus = 'DRAFT' | 'SUBMITTED' | 'RETURNED' | 'APPROVED' | 'SUPERSEDED'
export type TalentSubmissionType = 'SKILL' | 'KNOWLEDGE' | 'CAREER' | 'CERTIFICATION'
export const talentSubmissionTypeLabels: Record<TalentSubmissionType, string> = {
  SKILL: 'スキル',
  KNOWLEDGE: '得意分野',
  CAREER: '業務経歴',
  CERTIFICATION: '資格',
}
export const talentCategoryRoutes: Record<TalentSubmissionType, string> = {
  SKILL: '/skills',
  KNOWLEDGE: '/skills',
  CAREER: '/careers',
  CERTIFICATION: '/certifications',
}
export const talentSubmissionStatusLabels: Record<TalentSubmissionStatus, string> = {
  DRAFT: '下書き',
  SUBMITTED: '申請中',
  RETURNED: '差戻し',
  APPROVED: '承認済み',
  SUPERSEDED: '旧版',
}
export const talentVerificationStatusLabels: Record<string, string> = {
  VERIFIED: '確認済み',
}
export const employmentStatusLabels: Record<string, string> = {
  ACTIVE: '在籍',
  LEAVE: '休職',
  RETIRED: '退職',
}
export const talentMasterCategoryLabels: Record<string, string> = {
  ENGINEERING: 'エンジニアリング',
  PLATFORM: '基盤・インフラ',
  QUALITY: '品質保証',
  DATA: 'データ',
  PRODUCT: 'プロダクト',
  BUSINESS: 'ビジネス',
  DELIVERY: '企画・推進',
  SALES: '営業',
  CUSTOMER: '顧客支援',
  PEOPLE: '人材・組織',
  CORPORATE: 'コーポレート',
  GOVERNANCE: 'ガバナンス',
}
export type TalentMasterChoice = { publicId: string; code: string; name: string }
export type TalentSubmissionPayload = Partial<{
  masterPublicId: string
  level: number
  yearsExperience: number
  lastUsedOn: string
  evidence: string
  projectName: string
  industry: string
  roleName: string
  startDate: string
  endDate: string | null
  summary: string
  achievements: string
  technologies: string
  acquiredOn: string
  expiresOn: string | null
  credentialReference: string | null
}>
export type TalentSubmission = {
  publicId: string; logicalPublicId: string; type: TalentSubmissionType; revisionNo: number
  status: TalentSubmissionStatus; version: number; returnReason: string | null
  payload: TalentSubmissionPayload; submittedAt: string | null; decidedAt: string | null
}
export type ManagerTalentItem = { publicId: string; type: TalentSubmissionType; status: TalentSubmissionStatus; version: number; submittedAt: string; employeePublicId: string; employeeName: string }
export type TalentAttachmentScanStatus = 'PENDING' | 'CLEAN' | 'INFECTED' | 'ERROR'
export type TalentAttachmentSummary = { publicId: string; fileName: string; contentType: string; sizeBytes: number; scanStatus: TalentAttachmentScanStatus }
export type TalentAttachmentUpload = TalentAttachmentSummary & { submissionVersion: number }
export type TalentAttachment = TalentAttachmentSummary
export type ManagerTalentDetail = { submission: TalentSubmission; employee: { publicId: string; displayName: string }; approvedPredecessorPayload: TalentSubmissionPayload | null; attachments: TalentAttachment[]; events: Array<{ action: string; fromStatus: string | null; toStatus: string; reason: string | null; occurredAt: string }> }
export type MasterRequest = {
  publicId: string
  type: string
  description: string
  status: 'SUBMITTED' | 'APPROVED' | 'RETURNED'
  version: number
  returnReason: string | null
  requestedAt: string
  decidedAt: string | null
  createdMasterPublicId: string | null
  requesterName: string
}
