import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 配置 marked
marked.setOptions({
  breaks: true,       // 换行符转 <br>
  gfm: true,          // GitHub Flavored Markdown
})

/**
 * 预处理 SemiMind 平台特有的标记：
 * 1. ###数字$$ -> 引用标记，转为上标 [数字]
 * 2. ~~2$$ -> 加载光标标记，直接移除
 * 3. 全角：！ -> 半角！(修复图片语法)
 * 4. ![](file.ext) -> ![](file.ext) (补全缺失的图片语法)
 */
function preprocessSemiMindMarkers(text) {
  if (!text) return text
  // 移除加载光标标记 ~~2$$ (~数字$$)
  text = text.replace(/~~\d+\$\$/g, '')
  // 将引用标记 ###数字$$ 转为上标引用 [数字]
  text = text.replace(/###(\d+)\$\$/g, '<sup>[$1]</sup>')
  // 将引用标记 [[数字]] 转为上标引用 [数字]
  text = text.replace(/\[\[(\d+)\]\]/g, '<sup>[$1]</sup>')
  // 修复全角感叹号的图片语法：！[...](...) -> ![...](...)
  text = text.replace(/！(\[[^\]]*\]\([^)]*\))/g, '!$1')
  // 补全缺失感叹号的图片引用： ![[xxx.png]] -> ![](xxx.png)
  text = text.replace(/!\[\[\s*([^\]]+)\]\]/g, '![$1]($1)')
  // 仅匹配文本的链接且 href 为图片文件名（含扩展名），避免误伤普通超链接
  text = text.replace(/(?<!!)\[([^\]]+)\]\((MINIO-[A-Za-z0-9_-]+\.\w{2,5})\)/g, '![$1]($2)')
  return text
}

/**
 * 替换 MINIO 图片引用为实际图片 URL
 * 使用 imgMap 映射构建正确的 /img_v1/{bucket}-ragimage/{fname} 路径
 * 无 imgMap 时回退到 /img_v1/{key} 路径
 */
export function replaceMINIOReferences(text, envUrl, imgMap) {
  if (!text || !envUrl || !text.includes('MINIO-')) return text
  return text.replace(/MINIO-([A-Za-z0-9_\-]+\.\w+)/g, (match) => {
    if (imgMap && imgMap[match]) {
      return `http://${envUrl}/img_v1/${imgMap[match]}`
    }
    return `http://${envUrl}/img_v1/${match.substring(6)}`
  })
}

/**
 * 替换裸图片文件名引用为实际图片 URL
 * 处理 SemiMind 回答中不带 MINIO- 前缀的图片引用，如 ![](4YfaF-xxx.png)
 * 通过 imgMap 反查：MINIO-{filename} -> 实际路径
 */
function replaceBareImageReferences(text, envUrl, imgMap) {
  if (!text || !envUrl || !imgMap) return text
  for (const [minioKey, path] of Object.entries(imgMap)) {
    const bareKey = minioKey.replace(/^MINIO-/, '')
    if (text.includes(bareKey)) {
      const url = `http://${envUrl}/img_v1/${path}`
      text = text.replaceAll(bareKey, url)
    }
  }
  return text
}

/**
 * 将 markdown 文本渲染为安全的 HTML
 * @param {string} text markdown 文本
 * @param {string} envUrl 可选，SemiMind 环境 URL（用于替换 MINIO 图片引用）
 * @param {Object} imgMap 可选，MINIO 图片映射表（来自 elements_url_mapping）
 */
export function renderMarkdown(text, envUrl, imgMap) {
  if (!text) return ''
  let preprocessed = preprocessSemiMindMarkers(text)
  if (envUrl) {
    preprocessed = replaceMINIOReferences(preprocessed, envUrl, imgMap)
    preprocessed = replaceBareImageReferences(preprocessed, envUrl, imgMap)
  }
  const html = marked.parse(preprocessed)
  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['sup', 'sub', 'img'],
    ADD_ATTR: ['target', 'src', 'alt'],
  })
}
