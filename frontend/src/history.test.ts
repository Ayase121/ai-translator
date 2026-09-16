import { beforeEach, describe, expect, it } from 'vitest'
import { clearHistory, loadHistory, saveDocumentHistory, saveHistory, type HistoryItem } from './history'

describe('translation history', () => {
  beforeEach(() => localStorage.clear())

  it('keeps the newest 50 translations', () => {
    for (let index = 0; index < 52; index += 1) {
      saveHistory(item(index))
    }

    const history = loadHistory()
    expect(history).toHaveLength(50)
    expect(history[0]).toMatchObject({ kind: 'text', sourceText: 'source-51' })
    expect(history[49]).toMatchObject({ kind: 'text', sourceText: 'source-2' })
  })

  it('migrates legacy LinguaFlow history to the AI Translator key', () => {
    const legacy = { ...item(1) }
    delete (legacy as Partial<HistoryItem>).kind
    localStorage.setItem('linguaflow.translation-history', JSON.stringify([legacy]))

    expect(loadHistory()).toHaveLength(1)
    expect(loadHistory()[0]!.kind).toBe('text')
    expect(localStorage.getItem('ai-translator.translation-history')).not.toBeNull()
  })

  it('stores document translation metadata without document content', () => {
    saveDocumentHistory({
      id: 'doc-1',
      fileName: 'source.docx',
      outputFileName: 'source-translated.docx',
      sourceLanguage: 'en',
      targetLanguage: 'zh-CN',
      documentType: 'technical',
      translationStyle: 'formal',
      createdAt: new Date(1).toISOString(),
    })

    const [entry] = loadHistory()
    expect(entry).toMatchObject({ kind: 'document', fileName: 'source.docx', outputFileName: 'source-translated.docx' })
    expect(entry).not.toHaveProperty('sourceText')
    expect(entry).not.toHaveProperty('translatedText')
  })

  it('accepts legacy text entries when saving and normalizes their kind', () => {
    saveHistory({
      id: 'legacy-save',
      sourceText: 'source',
      translatedText: 'target',
      sourceLanguage: 'auto',
      targetLanguage: 'en',
      createdAt: new Date(3).toISOString(),
    })

    expect(loadHistory()[0]).toMatchObject({ kind: 'text', sourceText: 'source' })
  })

  it('clears all local history', () => {
    saveHistory(item(1))
    saveDocumentHistory({
      id: 'doc-1',
      fileName: 'source.docx',
      outputFileName: 'source-translated.docx',
      sourceLanguage: 'en',
      targetLanguage: 'zh-CN',
      documentType: 'general',
      translationStyle: 'natural',
      createdAt: new Date(2).toISOString(),
    })
    clearHistory()
    expect(loadHistory()).toEqual([])
  })
})

function item(index: number): HistoryItem {
  return {
    id: String(index),
    sourceText: `source-${index}`,
    translatedText: `target-${index}`,
    kind: 'text',
    sourceLanguage: 'auto',
    targetLanguage: 'en',
    createdAt: new Date(index).toISOString(),
  }
}
