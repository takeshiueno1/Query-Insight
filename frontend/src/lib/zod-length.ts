import { z } from 'zod'

// Java Bean Validation and the existing API limits count UTF-16 code units.
// Zod 4.6 counts code points for its built-in string length checks.
export function utf16Max(maximum: number, message?: string) {
  return z.superRefine<string>((value, context) => {
    if (value.length > maximum) {
      context.addIssue({ code: 'too_big', origin: 'string', maximum, inclusive: true, ...(message ? { message } : {}) })
    }
  })
}
