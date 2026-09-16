import axios from 'axios'

export interface LanguageOption {
  code: string
  name: string
  sourceOnly: boolean
  ocrSupported: boolean
}

export interface TranslationResponse {
  translatedText: string
  sourceLanguage: string
  targetLanguage: string
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 300_000,
})

export async function getLanguages(): Promise<LanguageOption[]> {
  return (await client.get<LanguageOption[]>('/api/v1/languages')).data
}

export async function translateText(
  text: string,
  sourceLanguage: string,
  targetLanguage: string,
): Promise<TranslationResponse> {
  return (await client.post<TranslationResponse>('/api/v1/translations/text', {
    text,
    sourceLanguage,
    targetLanguage,
  })).data
}

export async function translateDocument(
  file: File,
  sourceLanguage: string,
  targetLanguage: string,
  options: { documentType: string; translationStyle: string; protectedTerms: string[] },
): Promise<{ blob: Blob; filename: string; partial: boolean; coverage: string }> {
  const form = new FormData()
  form.append('file', file)
  form.append('sourceLanguage', sourceLanguage)
  form.append('targetLanguage', targetLanguage)
  form.append('documentType', options.documentType)
  form.append('translationStyle', options.translationStyle)
  options.protectedTerms.forEach((term) => form.append('protectedTerms', term))
  const response = await client.post('/api/v1/translations/document', form, { responseType: 'blob' })
  const disposition = response.headers['content-disposition'] as string | undefined
  const encoded = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  return {
    blob: response.data as Blob,
    filename: encoded ? decodeURIComponent(encoded) : 'translated.docx',
    partial: response.headers['x-translation-status'] === 'partial',
    coverage: (response.headers['x-translation-coverage'] as string | undefined) || '',
  }
}

export async function readableError(error: unknown): Promise<string> {
  if (!axios.isAxiosError(error)) return '发生未知错误，请稍后重试'
  if (error.response?.data instanceof Blob) {
    try {
      const problem = JSON.parse(await error.response.data.text()) as { detail?: string }
      return problem.detail || '请求失败，请稍后重试'
    } catch {
      return '请求失败，请稍后重试'
    }
  }
  const data = error.response?.data as { detail?: string } | undefined
  return data?.detail || (error.code === 'ECONNABORTED' ? '请求超时，请缩短内容后重试' : '无法连接翻译服务')
}
