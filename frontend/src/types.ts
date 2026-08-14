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

export type Dashboard = {
  profile: {
    employeeNo: string
    name: string
    department: string | null
    positionName: string | null
    updatedAt: string
  }
  scores: Score[]
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
  selfLevel: number | null
  selfEvidence: string | null
  managerLevel: number | null
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
  score: number | null
  grade: string | null
  details: EvaluationComparisonDetail[]
}

export type ExecutiveDashboard = {
  counts: { total: number; pending: number; finalized: number; overdue: number }
  items: Array<{ publicId: string; employeeName: string; departmentName: string | null; status: string; version: number; finalScore: number | null; finalGrade: string | null; periodName: string; late: boolean }>
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
  score: number | null
  grade: string | null
  finalScore: number | null
  finalGrade: string | null
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
export type TalentMasterChoice = { publicId: string; code: string; name: string }
export type TalentSubmission = {
  publicId: string; logicalPublicId: string; type: TalentSubmissionType; revisionNo: number
  status: TalentSubmissionStatus; version: number; returnReason: string | null
  payload: Record<string, unknown>; submittedAt: string | null; decidedAt: string | null
}
export type ManagerTalentItem = { publicId: string; type: TalentSubmissionType; status: TalentSubmissionStatus; version: number; submittedAt: string; employeePublicId: string; employeeName: string }
export type ManagerTalentDetail = { submission: TalentSubmission; employee: { publicId: string; displayName: string }; approvedPredecessorPayload: Record<string, unknown> | null; attachments: Array<{ publicId: string; fileName: string; contentType: string; sizeBytes: number; scanStatus: string }>; events: Array<{ action: string; fromStatus: string | null; toStatus: string; reason: string | null; occurredAt: string }> }
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
