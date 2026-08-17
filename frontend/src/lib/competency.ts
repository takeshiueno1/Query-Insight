export const competencyLevels = [
  { value: '1', label: '学習中', description: '支援を受けながら取り組める' },
  { value: '2', label: '基礎', description: '基本的な手順を理解している' },
  { value: '3', label: '自立', description: '通常業務を自力で進められる' },
  { value: '4', label: '高度', description: '複雑な課題を解決できる' },
  { value: '5', label: '指導', description: '周囲を指導し、標準化を進められる' },
] as const

export function competencyLevelLabel(value: unknown) {
  return competencyLevels.find((item) => Number(item.value) === Number(value))?.label ?? '未設定'
}
