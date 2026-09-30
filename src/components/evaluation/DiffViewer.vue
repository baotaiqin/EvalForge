<template>
  <div class="diff-viewer">
    <div class="diff-header">
      <span class="diff-label expected">预期结果</span>
      <span class="diff-label actual">实际结果</span>
    </div>
    <div class="diff-body">
      <div class="diff-pane expected-pane">
        <div v-if="expected" class="diff-content">
          <span
            v-for="(segment, index) in diffSegments"
            :key="`expected-${index}`"
            :class="segment.type === 'removed' ? 'diff-removed' : 'diff-equal'"
            v-html="segment.type === 'added' ? '' : renderWithImages(segment.text)"
          />
        </div>
        <div v-else class="diff-empty">无预期结果</div>
      </div>
      <div class="diff-pane actual-pane">
        <div class="diff-content">
          <span
            v-for="(segment, index) in diffSegments"
            :key="`actual-${index}`"
            :class="segment.type === 'added' ? 'diff-added' : 'diff-equal'"
            v-html="segment.type === 'removed' ? '' : renderWithImages(segment.text)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { BASE_URL } from '@/api/request.js'

const props = defineProps({
  expected: { type: String, default: '' },
  actual: { type: String, default: '' }
})

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function renderWithImages(text) {
  if (!text) return ''
  const imagePattern = /!\[([^\]]*)\]\(([^)]+)\)/g
  const matches = [...String(text).matchAll(imagePattern)]
  if (!matches.length) return escapeHtml(text).replace(/\n/g, '<br/>')

  let result = ''
  let lastIndex = 0
  for (const match of matches) {
    result += escapeHtml(text.slice(lastIndex, match.index)).replace(/\n/g, '<br/>')
    const alt = escapeHtml(match[1])
    const source = match[2].trim()
    const url = source.startsWith('/api/') ? `${BASE_URL}${source}` : source
    result += `<img src="${escapeHtml(url)}" alt="${alt}" style="max-width:100%;max-height:200px;object-fit:contain;border-radius:4px;margin:4px 0;display:block;" />`
    lastIndex = match.index + match[0].length
  }
  result += escapeHtml(text.slice(lastIndex)).replace(/\n/g, '<br/>')
  return result
}

function splitSentences(text) {
  if (!text) return []
  return String(text).split(/(?<=[。！？.!?；;])\s*|(?<=\n)/).filter(part => part.length > 0)
}

const diffSegments = computed(() => {
  if (!props.expected) return props.actual ? [{ type: 'added', text: props.actual }] : []

  const expectedParts = splitSentences(props.expected)
  const actualParts = splitSentences(props.actual)
  const segments = []
  const maxLength = Math.max(expectedParts.length, actualParts.length)
  for (let index = 0; index < maxLength; index += 1) {
    const expectedPart = expectedParts[index] || ''
    const actualPart = actualParts[index] || ''
    if (expectedPart === actualPart) {
      if (expectedPart) segments.push({ type: 'equal', text: expectedPart })
    } else {
      if (expectedPart) segments.push({ type: 'removed', text: expectedPart })
      if (actualPart) segments.push({ type: 'added', text: actualPart })
    }
  }
  return segments
})
</script>

<style scoped>
.diff-viewer {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-size: 13px;
}

.diff-header,
.diff-body {
  display: flex;
}

.diff-header {
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
}

.diff-label {
  flex: 1;
  padding: 8px 12px;
  font-weight: 600;
  font-size: 12px;
}

.diff-label.expected {
  color: #cf1322;
  border-right: 1px solid #e8e8e8;
}

.diff-label.actual {
  color: #389e0d;
}

.diff-body {
  min-height: 60px;
}

.diff-pane {
  flex: 1;
  min-width: 0;
  padding: 8px 12px;
  line-height: 1.8;
  word-break: break-all;
}

.expected-pane {
  background: #fff1f0;
  border-right: 1px solid #e8e8e8;
}

.actual-pane {
  background: #f6ffed;
}

.diff-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-removed {
  background: #ffa39e;
  padding: 1px 2px;
  border-radius: 2px;
  text-decoration: line-through;
}

.diff-added {
  background: #b7eb8f;
  padding: 1px 2px;
  border-radius: 2px;
}

.diff-equal {
  color: #595959;
}

.diff-empty {
  color: #999;
  font-style: italic;
}
</style>
