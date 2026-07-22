import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { EmployeePage } from '../types'

export function EmployeesPage() {
  const [input, setInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['employees', keyword, page], queryFn: () => api<EmployeePage>(`/api/v1/employees?keyword=${encodeURIComponent(keyword)}&page=${page}&size=20`) })
  return (
    <>
      <div className="page-heading"><div><span className="eyebrow">SCR-003</span><h1>社員検索</h1><p>権限範囲内の社員のみ表示されます</p></div><Link className="primary-button" to="/employees/new">社員登録</Link></div>
      <form className="search-card" onSubmit={(event) => { event.preventDefault(); setPage(0); setKeyword(input.trim()) }}>
        <label>キーワード<input value={input} onChange={(event) => setInput(event.target.value)} maxLength={100} placeholder="氏名・社員番号・メール" /></label>
        <button className="primary-button">検索</button>
      </form>
      <section className="card table-card">
        <div className="card-header"><div><h2>検索結果</h2><p>{query.data?.totalElements ?? 0}件</p></div></div>
        {query.isLoading ? <p className="empty">検索中…</p> : query.isError ? <p className="error-banner">社員一覧を取得できませんでした。</p> : query.data?.content.length === 0 ? <p className="empty">条件に一致する社員はいません。検索条件を変更してください。</p> :
          <div className="table-scroll"><table><thead><tr><th>社員番号</th><th>氏名</th><th>所属</th><th>役職</th><th>状態</th><th>操作</th></tr></thead><tbody>
            {query.data?.content.map((employee) => <tr key={employee.publicId}><td>{employee.employeeNo}</td><td>{employee.name}</td><td>{employee.departmentName ?? '未設定'}</td><td>{employee.positionName ?? '未設定'}</td><td><Status value={employee.employmentStatus} /></td><td><Link className="text-link" to={`/employees/${employee.publicId}`}>詳細を見る</Link></td></tr>)}
          </tbody></table></div>}
        {query.data && query.data.totalPages > 1 && <div className="pagination"><button className="ghost-button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>前へ</button><span>{page + 1} / {query.data.totalPages}</span><button className="ghost-button" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage((value) => value + 1)}>次へ</button></div>}
      </section>
    </>
  )
}

function Status({ value }: { value: string }) { return <span className={`status ${value === 'ACTIVE' ? 'success' : 'warning'}`}>{value}</span> }
