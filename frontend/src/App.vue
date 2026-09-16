<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLanguages, readableError, translateDocument, translateText, type LanguageOption } from './api'
import { clearHistory, loadHistory, saveDocumentHistory, saveHistory, type HistoryItem } from './history'

const fallbackLanguages: LanguageOption[] = [
  { code: 'auto', name: '自动检测', sourceOnly: true, ocrSupported: true },
  { code: 'zh-CN', name: '简体中文', sourceOnly: false, ocrSupported: true },
  { code: 'en', name: '英语', sourceOnly: false, ocrSupported: true },
  { code: 'ja', name: '日语', sourceOnly: false, ocrSupported: false },
  { code: 'ko', name: '韩语', sourceOnly: false, ocrSupported: false },
  { code: 'fr', name: '法语', sourceOnly: false, ocrSupported: false },
  { code: 'de', name: '德语', sourceOnly: false, ocrSupported: false },
  { code: 'es', name: '西班牙语', sourceOnly: false, ocrSupported: false },
  { code: 'ru', name: '俄语', sourceOnly: false, ocrSupported: false },
]

const activeTab = ref('text')
const languages = ref<LanguageOption[]>(fallbackLanguages)
const sourceLanguage = ref('auto')
const targetLanguage = ref('en')
const sourceText = ref('')
const translatedText = ref('')
const translating = ref(false)
const documentFile = ref<File>()
const documentTranslating = ref(false)
const documentType = ref('general')
const translationStyle = ref('natural')
const protectedTermsText = ref('')
const history = ref<HistoryItem[]>(loadHistory())
const fileInput = ref<HTMLInputElement>()

const targetLanguages = computed(() => languages.value.filter((language) => !language.sourceOnly))
const canTranslate = computed(() => sourceText.value.trim().length > 0 && sourceText.value.length <= 20_000)

onMounted(async () => {
  try {
    languages.value = await getLanguages()
  } catch {
    ElMessage.warning('语言列表暂时无法同步，已使用内置列表')
  }
})

async function submitText(): Promise<void> {
  if (!canTranslate.value) return
  translating.value = true
  try {
    const result = await translateText(sourceText.value, sourceLanguage.value, targetLanguage.value)
    translatedText.value = result.translatedText
    history.value = saveHistory({
      id: crypto.randomUUID(),
      kind: 'text',
      sourceText: sourceText.value,
      translatedText: result.translatedText,
      sourceLanguage: result.sourceLanguage,
      targetLanguage: result.targetLanguage,
      createdAt: new Date().toISOString(),
    })
  } catch (error) {
    ElMessage.error(await readableError(error))
  } finally {
    translating.value = false
  }
}

async function copyTranslation(): Promise<void> {
  if (!translatedText.value) return
  await navigator.clipboard.writeText(translatedText.value)
  ElMessage.success('已复制')
}

function swapLanguages(): void {
  if (sourceLanguage.value === 'auto') {
    sourceLanguage.value = targetLanguage.value
    targetLanguage.value = 'zh-CN'
  } else {
    ;[sourceLanguage.value, targetLanguage.value] = [targetLanguage.value, sourceLanguage.value]
  }
  ;[sourceText.value, translatedText.value] = [translatedText.value, sourceText.value]
}

function selectFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10 MB')
    input.value = ''
    return
  }
  documentFile.value = file
}

async function submitDocument(): Promise<void> {
  if (!documentFile.value) return
  documentTranslating.value = true
  try {
    const sourceFileName = documentFile.value.name
    const protectedTerms = protectedTermsText.value.split(/\r?\n/).map((term) => term.trim()).filter(Boolean)
    const result = await translateDocument(documentFile.value, sourceLanguage.value, targetLanguage.value, {
      documentType: documentType.value,
      translationStyle: translationStyle.value,
      protectedTerms,
    })
    const url = URL.createObjectURL(result.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename
    link.click()
    URL.revokeObjectURL(url)
    try {
      history.value = saveDocumentHistory({
        id: crypto.randomUUID(),
        fileName: sourceFileName,
        outputFileName: result.filename,
        sourceLanguage: sourceLanguage.value,
        targetLanguage: targetLanguage.value,
        documentType: documentType.value,
        translationStyle: translationStyle.value,
        createdAt: new Date().toISOString(),
      })
    } catch {
      ElMessage.warning('下载成功，但历史未保存')
    }
    if (result.partial) {
      const detail = sourceFileName.toLowerCase().endsWith('.docx')
        ? `半译稿已开始下载；片段覆盖 ${result.coverage}，部分片段保留原文`
        : `固定版式半译稿已开始下载；正文行中文覆盖 ${result.coverage}，其余区域保留原文`
      ElMessage.warning(detail)
    } else {
      ElMessage.success('翻译完成，DOCX 已开始下载')
    }
  } catch (error) {
    ElMessage.error(await readableError(error))
  } finally {
    documentTranslating.value = false
  }
}

function restoreHistory(item: HistoryItem): void {
  if (item.kind !== 'text') return
  sourceText.value = item.sourceText
  translatedText.value = item.translatedText
  sourceLanguage.value = item.sourceLanguage
  targetLanguage.value = item.targetLanguage
  activeTab.value = 'text'
}

async function removeHistory(): Promise<void> {
  await ElMessageBox.confirm('确定清空全部本地翻译记录吗？', '清空历史', { type: 'warning' })
  clearHistory()
  history.value = []
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">A</span>
        <div><strong>AI Translator</strong><small>DeepSeek AI Translator</small></div>
      </div>
      <span class="privacy-note">无需登录 · 历史仅保存在本机</span>
    </header>

    <main>
      <section class="hero">
        <span class="eyebrow">AI TRANSLATION WORKSPACE</span>
        <h1>让语言自然流动</h1>
        <p>文本、办公文档与扫描 PDF，一处完成准确翻译。</p>
      </section>

      <section class="workspace-card">
        <el-tabs v-model="activeTab" class="mode-tabs">
          <el-tab-pane label="文本翻译" name="text" />
          <el-tab-pane label="文档翻译" name="document" />
        </el-tabs>

        <div class="language-bar">
          <el-select v-model="sourceLanguage" aria-label="源语言">
            <el-option v-for="language in languages" :key="language.code" :label="language.name" :value="language.code" />
          </el-select>
          <button class="swap-button" type="button" aria-label="交换语言" @click="swapLanguages">⇄</button>
          <el-select v-model="targetLanguage" aria-label="目标语言">
            <el-option v-for="language in targetLanguages" :key="language.code" :label="language.name" :value="language.code" />
          </el-select>
        </div>

        <div v-if="activeTab === 'text'" class="text-workspace">
          <div class="editor-pane">
            <textarea v-model="sourceText" maxlength="20000" placeholder="输入或粘贴需要翻译的文字…" />
            <div class="pane-footer">
              <span>{{ sourceText.length.toLocaleString() }} / 20,000</span>
              <button type="button" @click="sourceText = ''; translatedText = ''">清空</button>
            </div>
          </div>
          <div class="editor-pane result-pane">
            <div v-if="translatedText" class="result-text">{{ translatedText }}</div>
            <div v-else class="placeholder">译文将在这里显示</div>
            <div class="pane-footer">
              <span>DeepSeek</span>
              <button type="button" :disabled="!translatedText" @click="copyTranslation">复制译文</button>
            </div>
          </div>
          <el-button class="translate-button" type="primary" :loading="translating" :disabled="!canTranslate" @click="submitText">
            开始翻译
          </el-button>
        </div>

        <div v-else class="document-workspace">
          <input ref="fileInput" hidden type="file" accept=".txt,.docx,.pdf" @change="selectFile" />
          <button class="drop-zone" type="button" @click="fileInput?.click()">
            <span class="upload-icon">↑</span>
            <strong>{{ documentFile?.name || '选择需要翻译的文档' }}</strong>
            <small>支持 TXT、DOCX、普通及扫描 PDF · 最大 10 MB</small>
          </button>
          <div class="ocr-tip">扫描 PDF OCR 仅支持中文和英文，其他语言扫描件可能无法正确识别。</div>
          <div class="document-options">
            <el-select v-model="documentType" aria-label="文档类型">
              <el-option label="通用文档" value="general" />
              <el-option label="商务文档" value="business" />
              <el-option label="技术文档" value="technical" />
              <el-option label="学术文档" value="academic" />
              <el-option label="法律文档" value="legal" />
              <el-option label="营销文案" value="marketing" />
            </el-select>
            <el-select v-model="translationStyle" aria-label="翻译风格">
              <el-option label="自然" value="natural" />
              <el-option label="忠实" value="faithful" />
              <el-option label="正式" value="formal" />
              <el-option label="简洁" value="concise" />
            </el-select>
          </div>
          <div class="document-controls">
            <el-input v-model="protectedTermsText" class="protected-terms" type="textarea" :rows="3" maxlength="10000" show-word-limit placeholder="可选：保护词，每行一个，翻译时保持原样" />
            <el-button class="document-button" type="primary" :loading="documentTranslating" :disabled="!documentFile" @click="submitDocument">
              翻译并下载 DOCX
            </el-button>
          </div>
        </div>
      </section>

      <section v-if="history.length" class="history-section">
        <div class="section-heading"><div><span>RECENT</span><h2>最近翻译</h2></div><button type="button" @click="removeHistory">清空记录</button></div>
        <div class="history-grid">
          <template v-for="item in history.slice(0, 6)" :key="item.id">
            <button v-if="item.kind === 'text'" class="history-card" type="button" @click="restoreHistory(item)">
              <span>{{ item.sourceLanguage }} → {{ item.targetLanguage }}</span>
              <p>{{ item.sourceText }}</p>
              <small>{{ new Date(item.createdAt).toLocaleString() }}</small>
            </button>
            <article v-else class="history-card">
              <span>文档 · {{ item.sourceLanguage }} → {{ item.targetLanguage }}</span>
              <p>{{ item.fileName }}</p>
              <small>{{ new Date(item.createdAt).toLocaleString() }} · 文件未保存在历史中</small>
            </article>
          </template>
        </div>
      </section>
    </main>

    <footer>AI Translator · 文件仅在请求期间处理，不做服务端存储</footer>
  </div>
</template>
