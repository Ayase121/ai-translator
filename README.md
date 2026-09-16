# AI Translator

AI Translator 是一个前后端分离的翻译 Web 应用：后端使用 Spring Boot MVC 和 Spring AI 接入 DeepSeek，前端使用 Vue 3、TypeScript、Vite 和 Element Plus。支持文本翻译，以及 TXT、DOCX、普通 PDF 和扫描 PDF 翻译。

DOCX 会复制原始文档并按段落翻译，只原位修改需要翻译的 `w:t`，保留原文档的样式和其他 OOXML。文档翻译支持文档类型、翻译风格和逐行保护词设置。

## 环境要求

- JDK 17 或更高版本
- Maven 3.6.3 或更高版本
- Node.js 20.19+ 或 22.12+
- DeepSeek API Key
- 扫描 PDF OCR 需要 Tesseract 5，并安装 `chi_sim`、`eng` 语言包

## 配置

应用配置文件为 `src/main/resources/application.yml`，通过环境变量提供 DeepSeek Key 和 OCR 路径：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      chat:
        model: ${DEEPSEEK_MODEL:deepseek-flash}

app:
  ocr:
    data-path: ${TESSDATA_PREFIX:"C:/Program Files/Tesseract-OCR/tessdata"}
```

扫描 PDF OCR 仅支持中文和英文，需要 `chi_sim.traineddata` 与 `eng.traineddata`；其他语言扫描件可能无法正确识别。

翻译历史仅保存在浏览器本机；文档历史只保存文件名、语言、时间和翻译策略等元数据，不保存译后文件。

## 启动后端

在 IntelliJ IDEA 中直接运行 `AiTranslatorApplication`，或在运行配置中设置相应环境变量。

命令行也可以使用：

```powershell
cd .
mvn spring-boot:run
```

后端默认地址：`http://localhost:8080`

## 启动前端

```powershell
cd .\frontend
npm install
npm run dev
```

前端默认地址：`http://localhost:5173`
