import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../lib/api'
import type { Problem, TalentSubmission, TalentSubmissionType } from '../types'
import { TalentSubmissionFormPage } from './TalentSubmissionFormPage'
import { TalentSubmissionHistoryPage } from './TalentSubmissionHistoryPage'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
vi.mock('../lib/api', () => ({
  api: apiMock,
  ApiError: class extends Error {
    problem: { detail: string }
    constructor(problem: { detail: string }) { super(problem.detail); this.problem = problem }
  },
}))

function renderType(type: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<MemoryRouter initialEntries={[`/talent/new/${type}`]}><QueryClientProvider client={client}><Routes><Route path="/talent/new/:type" element={<TalentSubmissionFormPage />} /><Route path="/talent/:type/:publicId/edit" element={<TalentSubmissionFormPage />} /></Routes></QueryClientProvider></MemoryRouter>)
}

function renderExisting(type: string, publicId: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<MemoryRouter initialEntries={[`/talent/${type}/${publicId}/edit`]}><QueryClientProvider client={client}><LocationProbe /><Routes><Route path="/talent/:type/:publicId/edit" element={<TalentSubmissionFormPage />} /></Routes></QueryClientProvider></MemoryRouter>)
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="current-path">{location.pathname}</div>
}

describe('TalentSubmissionFormPage', () => {
  beforeEach(() => apiMock.mockReset().mockResolvedValue([]))
  afterEach(cleanup)

  it.each([
    ['SKILL', '経験年数'], ['KNOWLEDGE', '習熟度（1～5）'], ['CAREER', '案件名'], ['CERTIFICATION', '取得日'],
  ])('%sの種類別入力欄を表示する', (type, label) => {
    renderType(type)
    expect(screen.getByLabelText(label)).toBeInTheDocument()
  })

  it.each([
    ['SKILL', 'スキルを登録', 'スキル'],
    ['KNOWLEDGE', '得意分野を登録', '得意分野'],
    ['CAREER', '業務経歴を登録', '案件名'],
    ['CERTIFICATION', '資格を登録', '資格'],
  ])('%sの見出しと入力名を自然な日本語で表示し内部名称を見せない', (type, heading, label) => {
    renderType(type)

    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByLabelText(label)).toBeInTheDocument()
    expect(screen.queryByText('専門知識')).not.toBeInTheDocument()
    expect(screen.queryByText('TALENT APPLICATION')).not.toBeInTheDocument()
    expect(screen.queryByText(type)).not.toBeInTheDocument()
  })

  it('4件または5MB超の添付をAPI送信前に拒否する', () => {
    renderType('CAREER')
    const input = screen.getByLabelText('根拠資料（任意）') as HTMLInputElement
    const files = [1, 2, 3, 4].map((value) => new File(['pdf'], `${value}.pdf`, { type: 'application/pdf' }))
    fireEvent.change(input, { target: { files } })
    expect(screen.getByRole('status')).toHaveTextContent('添付は3件まで')
    expect(apiMock).not.toHaveBeenCalled()

    const large = new File(['pdf'], 'large.pdf', { type: 'application/pdf' })
    Object.defineProperty(large, 'size', { value: 5_242_881 })
    fireEvent.change(input, { target: { files: [large] } })
    expect(screen.getByRole('status')).toHaveTextContent('1件5MB以内')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('根拠資料は任意で添付なしのまま提出できる', async () => {
    apiMock
      .mockResolvedValueOnce({ publicId: 'S1', logicalPublicId: 'L1', type: 'CAREER', revisionNo: 1, status: 'DRAFT', version: 0 })
      .mockResolvedValueOnce({ publicId: 'S1', logicalPublicId: 'L1', type: 'CAREER', revisionNo: 1, status: 'SUBMITTED', version: 1 })
    renderType('CAREER')
    expect(screen.getByLabelText('根拠資料（任意）')).toBeInTheDocument()
    for (const [label, value] of [
      ['案件名', '基幹刷新'], ['業界', '製造'], ['役割', '担当者'], ['開始日', '2025-04-01'],
      ['概要', '刷新を担当'], ['成果', '予定どおり完了'], ['利用技術', 'Java'],
    ]) fireEvent.change(screen.getByLabelText(label), { target: { value } })
    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))
    expect(apiMock).toHaveBeenCalledTimes(2)
    expect(apiMock.mock.calls.some(([url]) => String(url).includes('/attachments'))).toBe(false)
    expect(screen.getByText(/状態: 申請中/)).toBeInTheDocument()
    expect(screen.queryByText('SUBMITTED')).not.toBeInTheDocument()
  })

  it('既存下書きのpayloadとversionを読み込み同じpublicIdへ保存する', async () => {
    const draft = existingSubmission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1, 7)
    let updateRequest: RequestInit | undefined
    let createCalled = false
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([draft])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (path === '/api/v1/talent-submissions/DRAFT-1') { updateRequest = init; return Promise.resolve({ ...draft, version: 8 }) }
      if (path === '/api/v1/talent-submissions/SKILL' && init?.method === 'POST') createCalled = true
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'DRAFT-1')

    expect(await screen.findByDisplayValue('既存の根拠')).toBeInTheDocument()
    expect(screen.getByDisplayValue('4')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('下書きを保存しました'))
    expect(updateRequest?.method).toBe('PUT')
    expect(JSON.parse(String(updateRequest?.body))).toMatchObject({ version: 7, payload: { masterPublicId: 'MASTER-1', evidence: '既存の根拠' } })
    expect(createCalled).toBe(false)
  })

  it('差戻し申請は既存publicIdを更新し返された同一chainの新revisionを提出する', async () => {
    const returned = { ...existingSubmission('RETURNED', 'RETURNED-1', 'CHAIN-1', 2, 5), returnReason: '根拠を追記してください' }
    const revised = existingSubmission('DRAFT', 'DRAFT-2', 'CHAIN-1', 3, 0)
    const submitted = { ...revised, status: 'SUBMITTED' as const, version: 1 }
    let updateRequest: RequestInit | undefined
    let submitRequest: RequestInit | undefined
    let createCalled = false
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([returned])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (path === '/api/v1/talent-submissions/RETURNED-1') { updateRequest = init; return Promise.resolve(revised) }
      if (path === '/api/v1/talent-submissions/DRAFT-2/submit') { submitRequest = init; return Promise.resolve(submitted) }
      if (path === '/api/v1/talent-submissions/SKILL' && init?.method === 'POST') createCalled = true
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'RETURNED-1')

    expect(await screen.findByDisplayValue('既存の根拠')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))

    await waitFor(() => expect(updateRequest?.method).toBe('PUT'))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))
    expect(JSON.parse(String(updateRequest?.body))).toMatchObject({ version: 5 })
    expect(submitRequest?.method).toBe('POST')
    expect(JSON.parse(String(submitRequest?.body))).toEqual({ version: 0 })
    expect(createCalled).toBe(false)
    expect(screen.getByText(/第3版/)).toBeInTheDocument()
  })

  it('差戻し更新後にsubmitが失敗しても新DRAFTのpublicIdとversionで再試行する', async () => {
    const returned = { ...existingSubmission('RETURNED', 'RETURNED-1', 'CHAIN-1', 2, 5), returnReason: '根拠を追記してください' }
    const revised = existingSubmission('DRAFT', 'DRAFT-2', 'CHAIN-1', 3, 0)
    const updated = { ...revised, version: 1 }
    const submitted = { ...updated, status: 'SUBMITTED' as const, version: 2 }
    const updateTargets: string[] = []
    const submitVersions: number[] = []
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([returned])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (init?.method === 'PUT') {
        updateTargets.push(path)
        return Promise.resolve(path.endsWith('/RETURNED-1') ? revised : updated)
      }
      if (path === '/api/v1/talent-submissions/DRAFT-2/submit') {
        submitVersions.push(JSON.parse(String(init?.body)).version)
        return submitVersions.length === 1 ? Promise.reject(new Error('申請処理に失敗しました')) : Promise.resolve(submitted)
      }
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'RETURNED-1')
    await screen.findByDisplayValue('既存の根拠')

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    const submitError = await screen.findByRole('alert')
    expect(submitError).toHaveTextContent('下書きは保存されています。')
    expect(submitError).toHaveTextContent('申請処理に失敗しました')
    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/talent/SKILL/DRAFT-2/edit'))

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))

    expect(updateTargets).toEqual(['/api/v1/talent-submissions/RETURNED-1', '/api/v1/talent-submissions/DRAFT-2'])
    expect(submitVersions).toEqual([0, 1])
    expect(screen.getByText(/状態: 申請中/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下書き保存' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '直属上長へ申請' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('根拠')).toBeDisabled()
  })

  it('添付途中で失敗しても成功済み添付を繰り返さず最新versionから再試行する', async () => {
    const returned = { ...existingSubmission('RETURNED', 'RETURNED-1', 'CHAIN-1', 2, 5), returnReason: '根拠を追記してください' }
    const revised = existingSubmission('DRAFT', 'DRAFT-2', 'CHAIN-1', 3, 0)
    const updated = { ...revised, version: 2 }
    const submitted = { ...updated, status: 'SUBMITTED' as const, version: 4 }
    const updateVersions: number[] = []
    const uploadedFiles: string[] = []
    const uploadVersions: string[] = []
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([returned])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (init?.method === 'PUT') {
        updateVersions.push(JSON.parse(String(init.body)).version)
        return Promise.resolve(path.endsWith('/RETURNED-1') ? revised : updated)
      }
      if (path === '/api/v1/talent-submissions/DRAFT-2/attachments') {
        const form = init?.body as FormData
        uploadedFiles.push((form.get('file') as File).name)
        uploadVersions.push(String(form.get('version')))
        if (uploadedFiles.join(',') === 'first.pdf,second.pdf') return Promise.reject(new Error('2件目の添付に失敗しました'))
        return Promise.resolve({ publicId: `ATTACHMENT-${uploadedFiles.length}`, fileName: (form.get('file') as File).name,
          contentType: 'application/pdf', sizeBytes: 3, scanStatus: 'CLEAN',
          submissionVersion: uploadedFiles.length === 1 ? 1 : 3 })
      }
      if (path === '/api/v1/talent-submissions/DRAFT-2/submit') return Promise.resolve(submitted)
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'RETURNED-1')
    await screen.findByDisplayValue('既存の根拠')
    fireEvent.change(screen.getByLabelText('根拠資料（任意）'), { target: { files: [
      new File(['one'], 'first.pdf', { type: 'application/pdf' }),
      new File(['two'], 'second.pdf', { type: 'application/pdf' }),
    ] } })

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    const attachmentError = await screen.findByRole('alert')
    expect(attachmentError).toHaveTextContent('成功済みの添付は保存されています。')
    expect(attachmentError).toHaveTextContent('2件目の添付に失敗しました')
    expect(screen.getByText(/状態:/)).toHaveTextContent('状態: 下書き / 第3版')

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))

    expect(updateVersions).toEqual([5, 1])
    expect(uploadedFiles).toEqual(['first.pdf', 'second.pdf', 'second.pdf'])
    expect(uploadVersions).toEqual(['0', '1', '2'])
  })

  it('CLEAN保存後に応答を失っても最新versionを再取得して同一添付を安全に再照合する', async () => {
    let serverDraft = existingSubmission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1, 0)
    const updateVersions: number[] = []
    const uploadVersions: string[] = []
    let uploadCalls = 0
    let submitVersion: number | undefined
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([serverDraft])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (path === '/api/v1/talent-submissions/DRAFT-1' && init?.method === 'PUT') {
        const version = JSON.parse(String(init.body)).version as number
        updateVersions.push(version)
        serverDraft = { ...serverDraft, version: version + 1 }
        return Promise.resolve(serverDraft)
      }
      if (path === '/api/v1/talent-submissions/DRAFT-1/attachments') {
        const form = init?.body as FormData
        uploadVersions.push(String(form.get('version')))
        uploadCalls += 1
        if (uploadCalls === 1) {
          serverDraft = { ...serverDraft, version: 2 }
          return Promise.reject(new Error('応答を受信できませんでした'))
        }
        return Promise.resolve({ publicId: 'ATTACHMENT-1', fileName: 'lost.pdf', contentType: 'application/pdf', sizeBytes: 3, scanStatus: 'CLEAN', submissionVersion: serverDraft.version })
      }
      if (path === '/api/v1/talent-submissions/DRAFT-1/submit') {
        submitVersion = JSON.parse(String(init?.body)).version
        return Promise.resolve({ ...serverDraft, status: 'SUBMITTED', version: serverDraft.version + 1 })
      }
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'DRAFT-1')
    await screen.findByDisplayValue('既存の根拠')
    fireEvent.change(screen.getByLabelText('根拠資料（任意）'), { target: { files: [
      new File(['pdf'], 'lost.pdf', { type: 'application/pdf' }),
    ] } })

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('応答を受信できませんでした')

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))

    expect(updateVersions).toEqual([0, 2])
    expect(uploadVersions).toEqual(['1', '3'])
    expect(uploadCalls).toBe(2)
    expect(submitVersion).toBe(3)
  })

  it('scanner 503で保存されたERROR添付は再送せず検査待ちとして回復する', async () => {
    let serverDraft = existingSubmission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1, 0)
    const updateVersions: number[] = []
    const uploadVersions: string[] = []
    const submitVersions: number[] = []
    let uploadCalls = 0
    const scanProblem: Problem = { title: '検査失敗', detail: 'ファイル検査を完了できませんでした。時間をおいて再試行します', status: 503, code: 'ATTACHMENT_SCAN_UNAVAILABLE' }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.resolve([serverDraft])
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (path === '/api/v1/talent-submissions/DRAFT-1' && init?.method === 'PUT') {
        const version = JSON.parse(String(init.body)).version as number
        updateVersions.push(version)
        serverDraft = { ...serverDraft, version: version + 1 }
        return Promise.resolve(serverDraft)
      }
      if (path === '/api/v1/talent-submissions/DRAFT-1/attachments') {
        const form = init?.body as FormData
        uploadVersions.push(String(form.get('version')))
        uploadCalls += 1
        return uploadCalls === 1
          ? Promise.reject(new ApiError(scanProblem))
          : Promise.resolve({ publicId: 'ATTACHMENT-1', fileName: 'scan.pdf', contentType: 'application/pdf', sizeBytes: 3, scanStatus: 'ERROR', submissionVersion: serverDraft.version })
      }
      if (path === '/api/v1/talent-submissions/DRAFT-1/submit') {
        const version = JSON.parse(String(init?.body)).version as number
        submitVersions.push(version)
        return Promise.resolve({ ...serverDraft, status: 'SUBMITTED', version: version + 1 })
      }
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'DRAFT-1')
    await screen.findByDisplayValue('既存の根拠')
    fireEvent.change(screen.getByLabelText('根拠資料（任意）'), { target: { files: [
      new File(['pdf'], 'scan.pdf', { type: 'application/pdf' }),
    ] } })

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('ファイル検査を完了できませんでした')
    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('添付ファイルは保存され、検査完了を待っています'))
    expect(submitVersions).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: '直属上長へ申請' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('直属上長へ申請しました'))

    expect(updateVersions).toEqual([0, 1, 2])
    expect(uploadVersions).toEqual(['1', '2'])
    expect(uploadCalls).toBe(2)
    expect(submitVersions).toEqual([3])
  })

  it('既存申請の取得中と取得失敗を支援技術へ通知する', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/talent-masters/SKILL'
      ? Promise.resolve([])
      : new Promise(() => undefined))
    const { unmount } = renderExisting('SKILL', 'DRAFT-1')
    expect(screen.getByRole('status')).toHaveTextContent('申請内容を読み込んでいます…')
    unmount()

    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([])
      if (path === '/api/v1/talent-submissions/me?type=SKILL') return Promise.reject(new Error('取得失敗'))
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'DRAFT-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('申請内容を取得できませんでした。')
  })

  it('版競合のAPI詳細を利用者へ表示する', async () => {
    const draft = existingSubmission('DRAFT', 'DRAFT-1', 'CHAIN-1', 1, 7)
    const problem: Problem = { title: '競合', detail: '別の操作で更新されています。再読み込みしてください。', status: 409, code: 'OPTIMISTIC_LOCK_CONFLICT' }
    let mineCalls = 0
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/talent-submissions/me?type=SKILL') { mineCalls += 1; return Promise.resolve([draft]) }
      if (path === '/api/v1/talent-masters/SKILL') return Promise.resolve([{ publicId: 'MASTER-1', code: 'SK001', name: 'Java' }])
      if (path === '/api/v1/talent-submissions/DRAFT-1') return Promise.reject(new ApiError(problem))
      return Promise.resolve([])
    })
    renderExisting('SKILL', 'DRAFT-1')
    await screen.findByDisplayValue('既存の根拠')

    fireEvent.click(screen.getByRole('button', { name: '下書き保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(problem.detail)
    expect(mineCalls).toBe(2)
  })
})

describe('TalentSubmissionHistoryPage', () => {
  beforeEach(() => apiMock.mockReset())
  afterEach(cleanup)

  it.each([
    ['SKILL', '/skills'], ['KNOWLEDGE', '/skills'], ['CAREER', '/careers'], ['CERTIFICATION', '/certifications'],
  ] as const)('%sの履歴から対応するカテゴリ画面へ戻る', async (type, backPath) => {
    const item = historySubmission(type)
    apiMock.mockImplementation((path: string) => String(path).includes('/history') ? Promise.resolve([item]) : Promise.resolve([]))
    renderHistory()

    expect(await screen.findByRole('link', { name: /一覧へ戻る/ })).toHaveAttribute('href', backPath)
    expect(screen.getByText(/第2版.*差戻し/)).toBeInTheDocument()
    expect(screen.queryByText('RETURNED')).not.toBeInTheDocument()
  })

  it('履歴payloadを日本語の構造化項目で表示しJSONと内部keyを見せない', async () => {
    apiMock.mockImplementation((path: string) => String(path).includes('/history') ? Promise.resolve([historySubmission('CAREER')]) : Promise.resolve([]))
    renderHistory()

    expect(await screen.findByText('基幹刷新')).toBeInTheDocument()
    expect(screen.getByText('案件名')).toBeInTheDocument()
    expect(screen.queryByText(/projectName|"projectName"|\{/)).not.toBeInTheDocument()
  })
})

function renderHistory() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<MemoryRouter initialEntries={['/talent/CHAIN-1/history']}><QueryClientProvider client={client}><Routes><Route path="/talent/:logicalPublicId/history" element={<TalentSubmissionHistoryPage />} /></Routes></QueryClientProvider></MemoryRouter>)
}

function existingSubmission(status: TalentSubmission['status'], publicId: string, logicalPublicId: string, revisionNo: number, version: number): TalentSubmission {
  return {
    publicId, logicalPublicId, revisionNo, version, status, type: 'SKILL', returnReason: null,
    payload: { masterPublicId: 'MASTER-1', level: 4, yearsExperience: 5, lastUsedOn: '2026-08-01', evidence: '既存の根拠' },
    submittedAt: status === 'DRAFT' ? null : '2026-08-14T00:00:00Z', decidedAt: null,
  }
}

function historySubmission(type: TalentSubmissionType): TalentSubmission {
  const payloads: Record<TalentSubmissionType, Record<string, unknown>> = {
    SKILL: { masterPublicId: 'MASTER-1', level: 4, yearsExperience: 5, lastUsedOn: '2026-08-01', evidence: '根拠' },
    KNOWLEDGE: { masterPublicId: 'MASTER-2', level: 3, evidence: '根拠' },
    CAREER: { projectName: '基幹刷新', industry: '製造', roleName: '担当者', startDate: '2025-04-01', endDate: null, summary: '刷新を担当', achievements: '完了', technologies: 'Java' },
    CERTIFICATION: { masterPublicId: 'MASTER-3', acquiredOn: '2024-04-01', expiresOn: null, credentialReference: null },
  }
  return { publicId: 'RETURNED-1', logicalPublicId: 'CHAIN-1', revisionNo: 2, version: 5, status: 'RETURNED', type, returnReason: '根拠を追記してください', payload: payloads[type], submittedAt: '2026-08-14T00:00:00Z', decidedAt: '2026-08-14T01:00:00Z' }
}
