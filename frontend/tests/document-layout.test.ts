import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('document translation controls layout', () => {
  it('keeps the optional terms field narrow and the download action below it', () => {
    const template = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')
    const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

    expect(template).toContain('<div class="document-controls">')
    expect(template.indexOf('class="protected-terms"')).toBeLessThan(template.indexOf('class="document-button"'))
    expect(styles).toMatch(/\.document-controls \{[^}]*display: flex;[^}]*flex-direction: column;[^}]*align-items: center;/)
    expect(styles).toMatch(/\.protected-terms \{[^}]*width: min\(100%, 420px\)/)
  })
})
