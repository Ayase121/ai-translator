interface HistoryBaseItem {
  id: string
  sourceLanguage: string
  targetLanguage: string
  createdAt: string
}

export interface TextHistoryItem extends HistoryBaseItem {
  kind: 'text'
  sourceText: string
  translatedText: string
}

export interface DocumentHistoryItem extends HistoryBaseItem {
  kind: 'document'
  fileName: string
  outputFileName: string
  documentType: string
  translationStyle: string
}

export type HistoryItem = TextHistoryItem | DocumentHistoryItem
export type HistoryItemInput = HistoryItem | Omit<TextHistoryItem, 'kind'>

const STORAGE_KEY = 'ai-translator.translation-history'
const LEGACY_STORAGE_KEY = 'linguaflow.translation-history'
const MAX_ITEMS = 50

export function loadHistory(): HistoryItem[] {
  try {
    const current = localStorage.getItem(STORAGE_KEY)
    const legacy = current ? null : localStorage.getItem(LEGACY_STORAGE_KEY)
    const value = current ?? legacy
    if (!value) return []
    const history = (JSON.parse(value) as unknown[])
      .map(normalizeHistoryItem)
      .filter((item): item is HistoryItem => item !== null)
    if (!current && legacy) localStorage.setItem(STORAGE_KEY, JSON.stringify(history))
    return history
  } catch {
    return []
  }
}

function normalizeHistoryItem(value: unknown): HistoryItem | null {
  if (!value || typeof value !== 'object') return null
  const item = value as Partial<TextHistoryItem> & Partial<DocumentHistoryItem> & { kind?: string }
  if (typeof item.id !== 'string' || typeof item.sourceLanguage !== 'string'
      || typeof item.targetLanguage !== 'string' || typeof item.createdAt !== 'string') return null
  if (item.kind === 'document' && typeof item.fileName === 'string'
      && typeof item.outputFileName === 'string') {
    return {
      id: item.id,
      kind: 'document',
      sourceLanguage: item.sourceLanguage,
      targetLanguage: item.targetLanguage,
      createdAt: item.createdAt,
      fileName: item.fileName,
      outputFileName: item.outputFileName,
      documentType: typeof item.documentType === 'string' ? item.documentType : 'general',
      translationStyle: typeof item.translationStyle === 'string' ? item.translationStyle : 'natural',
    }
  }
  if (typeof item.sourceText !== 'string' || typeof item.translatedText !== 'string') return null
  return {
    id: item.id,
    kind: 'text',
    sourceText: item.sourceText,
    translatedText: item.translatedText,
    sourceLanguage: item.sourceLanguage,
    targetLanguage: item.targetLanguage,
    createdAt: item.createdAt,
  }
}

export function saveHistory(item: HistoryItemInput): HistoryItem[] {
  const normalized: HistoryItem = 'kind' in item
      ? item
      : { ...item, kind: 'text' }
  const updated = [normalized, ...loadHistory().filter((entry) => entry.id !== normalized.id)].slice(0, MAX_ITEMS)
  localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
  return updated
}

export function saveDocumentHistory(item: Omit<DocumentHistoryItem, 'kind'>): HistoryItem[] {
  return saveHistory({ ...item, kind: 'document' })
}


export function clearHistory(): void {
  localStorage.removeItem(STORAGE_KEY)
  localStorage.removeItem(LEGACY_STORAGE_KEY)
}
