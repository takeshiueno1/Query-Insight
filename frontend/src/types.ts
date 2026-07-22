export type User = {
  accountPublicId: string
  employeePublicId: string
  displayName: string
  roles: string[]
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
