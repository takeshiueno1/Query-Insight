import { describe, expect, it } from 'vitest'
import { z } from 'zod'

describe('Zod bounded collection resource guard', () => {
  it.each(['sync', 'async'] as const)('rejects oversized arrays before parsing their items (%s)', async (mode) => {
    let parsedItems = 0
    const schema = z.array(z.string().refine(() => { parsedItems += 1; return true })).max(2)
    const result = mode === 'async' ? await schema.safeParseAsync(['a', 'b', 'c']) : schema.safeParse(['a', 'b', 'c'])
    expect(result.success).toBe(false)
    expect(parsedItems).toBe(0)
    if (!result.success) expect(result.error.issues[0].code).toBe('too_big')
  })

  it('preserves valid boundary input and rejects oversized invalid items by collection size', () => {
    const schema = z.array(z.string()).max(2)
    expect(schema.parse(['a', 'b'])).toEqual(['a', 'b'])
    const result = schema.safeParse([1, 2, 3])
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues.map(issue => issue.code)).toEqual(['too_big'])
  })
})
