import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import { utf16Max } from './zod-length'

describe('UTF-16 string limits shared with Java validation', () => {
  it('keeps supplementary Unicode characters at the original code-unit boundary', () => {
    const schema = z.string().check(utf16Max(254))
    expect(schema.safeParse('𠮷'.repeat(127)).success).toBe(true)
    const over = schema.safeParse('𠮷'.repeat(128))
    expect(over.success).toBe(false)
    if (!over.success) expect(over.error.issues[0]).toMatchObject({ code: 'too_big', maximum: 254 })
  })
  it('preserves trim, ASCII limits and supplied Japanese messages', () => {
    const schema = z.string().trim().min(1).check(utf16Max(2, '2文字以内で入力してください'))
    expect(schema.parse(' ab ')).toBe('ab')
    expect(schema.safeParse('abc').success).toBe(false)
    const over = schema.safeParse('𠮷a')
    if (!over.success) expect(over.error.issues[0].message).toBe('2文字以内で入力してください')
  })
})
