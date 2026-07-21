import { useMutation, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import type { EmployeeDetail } from '../types'

export function EmployeeFormPage() {
  const navigate = useNavigate()
  const { publicId } = useParams()
  const editing = Boolean(publicId)
  const [error, setError] = useState<string | null>(null)
  const detail = useQuery({
    queryKey: ['employee', publicId],
    queryFn: () => api<EmployeeDetail>(`/api/v1/employees/${publicId}`),
    enabled: editing,
  })
  const mutation = useMutation({
    mutationFn: (body: Record<string, unknown>) => api<EmployeeDetail>(
      editing ? `/api/v1/employees/${publicId}` : '/api/v1/employees',
      { method: editing ? 'PUT' : 'POST', body: JSON.stringify(body) },
    ),
  })

  if (editing && detail.isPending) return <div className="card">社員情報を読み込んでいます…</div>
  if (editing && (detail.isError || !detail.data)) return <div className="error-banner" role="alert">社員情報を取得できませんでした</div>

  const current = detail.data
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    const data = new FormData(event.currentTarget)
    try {
      const saved = await mutation.mutateAsync({
        employeeNo: data.get('employeeNo'), lastName: data.get('lastName'), firstName: data.get('firstName'),
        email: data.get('email'), departmentCode: data.get('departmentCode') || null,
        positionName: data.get('positionName') || null, employmentStatus: data.get('employmentStatus'),
        hireDate: data.get('hireDate') || null, version: current?.version ?? 0,
      })
      navigate(`/employees/${saved.publicId}`)
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.problem.detail : `${editing ? '更新' : '登録'}できませんでした`)
    }
  }

  return <><div className="page-heading"><div><span className="eyebrow">SCR-005</span><h1>社員{editing ? '編集' : '登録'}</h1><p>社員番号とメールの重複、更新競合をサーバー側でも検証します</p></div></div>
    <form className="card form-grid" onSubmit={(event) => void submit(event)}>
      <label>社員番号<span>*</span><input name="employeeNo" required maxLength={30} defaultValue={current?.employeeNo} /></label>
      <label>姓<span>*</span><input name="lastName" required maxLength={50} defaultValue={current?.lastName} /></label>
      <label>名<span>*</span><input name="firstName" required maxLength={50} defaultValue={current?.firstName} /></label>
      <label>メール<span>*</span><input name="email" required type="email" maxLength={254} defaultValue={current?.email} /></label>
      <label>部署コード<input name="departmentCode" maxLength={30} placeholder="DEV" defaultValue={current?.departmentCode ?? ''} /></label>
      <label>役職<input name="positionName" maxLength={100} defaultValue={current?.positionName ?? ''} /></label>
      <label>在籍状態<select name="employmentStatus" defaultValue={current?.employmentStatus ?? 'ACTIVE'}><option value="ACTIVE">在籍</option><option value="LEAVE">休職</option><option value="RETIRED">退職</option></select></label>
      <label>入社日<input name="hireDate" type="date" defaultValue={current?.hireDate ?? ''} /></label>
      {error && <div className="error-banner form-wide" role="alert">{error}</div>}
      <div className="form-actions form-wide"><button type="button" className="secondary-button" onClick={() => navigate(-1)}>キャンセル</button><button className="primary-button" disabled={mutation.isPending}>{mutation.isPending ? '保存中…' : '保存'}</button></div>
    </form></>
}
