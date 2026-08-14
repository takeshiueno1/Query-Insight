import type { TalentMasterChoice, TalentSubmissionPayload, TalentSubmissionType } from '../types'
import { talentSubmissionTypeLabels } from '../types'

export function TalentSubmissionDetails({ type, payload, masters = [] }: {
  type: TalentSubmissionType
  payload: TalentSubmissionPayload
  masters?: TalentMasterChoice[]
}) {
  const fields = detailFields(type, payload, masters)
  return <dl className="detail-list" aria-label={`${talentSubmissionTypeLabels[type]}の申請内容`}>
    {fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
  </dl>
}

function detailFields(type: TalentSubmissionType, payload: TalentSubmissionPayload, masters: TalentMasterChoice[]): Array<[string, string]> {
  const selectedName = masters.find((item) => item.publicId === payload.masterPublicId)?.name ?? '選択項目を確認できません'
  switch (type) {
    case 'SKILL':
      return [
        ['スキル', selectedName],
        ['習熟度', text(payload.level)],
        ['経験年数', payload.yearsExperience === undefined ? '未設定' : `${payload.yearsExperience}年`],
        ['最終利用日', text(payload.lastUsedOn)],
        ['根拠', text(payload.evidence)],
      ]
    case 'KNOWLEDGE':
      return [['得意分野', selectedName], ['習熟度', text(payload.level)], ['根拠', text(payload.evidence)]]
    case 'CAREER':
      return [
        ['案件名', text(payload.projectName)],
        ['業界', text(payload.industry)],
        ['役割', text(payload.roleName)],
        ['開始日', text(payload.startDate)],
        ['終了日', text(payload.endDate)],
        ['概要', text(payload.summary)],
        ['成果', text(payload.achievements)],
        ['利用技術', text(payload.technologies)],
      ]
    case 'CERTIFICATION':
      return [
        ['資格', selectedName],
        ['取得日', text(payload.acquiredOn)],
        ['有効期限', text(payload.expiresOn)],
        ['資格番号', text(payload.credentialReference)],
      ]
  }
}

function text(value: string | number | null | undefined) {
  return value === null || value === undefined || value === '' ? '未設定' : String(value)
}
