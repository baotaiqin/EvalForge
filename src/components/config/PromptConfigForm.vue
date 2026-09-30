<template>
  <div>
    <a-card title="大模型评分提示词">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="占位符说明"
        description="文本类提示词支持 {{question}}（问题）/{{expected}}（预期答案）/{{answer}}（实际答案），PPT 内容提示词支持 {{expectedSlides}}（标准答案PPT文字）/{{generatedSlides}}（生成PPT文字）；HTML 内容提示词支持 {{expectedContent}}（标准答案报告正文）/{{generatedContent}}（生成报告正文）。请保留提示词末尾要求返回的 JSON 格式说明，否则可能导致评分解析失败，自动回退为公式评分。"
      />
      <a-form layout="vertical">
        <a-form-item label="文本类评测提示词">
          <a-textarea v-model:value="localCriteria.textJudgePrompt" :rows="10" />
          <a style="font-size: 12px;" @click="resetPrompt('text')">恢复默认</a>
        </a-form-item>
        <a-form-item label="PPT 对比评测提示词（仅用于内容维度）">
          <a-textarea v-model:value="localCriteria.pptJudgePrompt" :rows="8" />
          <a style="font-size: 12px;" @click="resetPrompt('ppt')">恢复默认</a>
        </a-form-item>
        <a-form-item label="HTML 报告对比评测提示词（仅用于内容维度）">
          <a-textarea v-model:value="localCriteria.htmlJudgePrompt" :rows="8" />
          <a style="font-size: 12px;" @click="resetPrompt('html')">恢复默认</a>
        </a-form-item>
        <div style="text-align: right;">
          <a-button type="primary" :loading="savingPrompt" @click="handleSavePrompt">保存提示词</a-button>
        </div>
      </a-form>
    </a-card>

    <a-card title="模型测评报告提示词" style="margin-top: 24px;">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="占位符说明"
        description="子评测报告提示词支持 {{subEvalName}}（子评测名称）/{{accuracy}}（子评测综合得分）/{{questionList}}（该子评测下所有题目的问题、得分、评分说明清单）；总评提示词支持 {{subEvalSummaryList}}（各子评测名称、得分、评价清单）。两者均直接输出评价文本，不需要 JSON 格式。"
      />
      <a-form layout="vertical">
        <a-form-item label="子评测报告提示词">
          <a-textarea v-model:value="localCriteria.subEvalReportPrompt" :rows="8" />
          <a style="font-size: 12px;" @click="resetPrompt('subEvalReport')">恢复默认</a>
        </a-form-item>
        <a-form-item label="总评提示词">
          <a-textarea v-model:value="localCriteria.overallReportPrompt" :rows="8" />
          <a style="font-size: 12px;" @click="resetPrompt('overallReport')">恢复默认</a>
        </a-form-item>
        <div style="text-align: right;">
          <a-button type="primary" :loading="savingReportPrompt" @click="handleSaveReportPrompt">保存提示词</a-button>
        </div>
      </a-form>
    </a-card>

    <a-card title="报告对比提示词" style="margin-top: 24px;">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="占位符说明"
        description="子评测维度对比提示词支持 {{subtypeName}}（子评测类型名称）/{{reportList}}（该维度下参与对比的各模型名称、得分、评价清单）；总维度提示词支持 {{subtypeComparisonList}}（各子评测维度对比分析清单）/{{reportOverallList}}（各模型综合得分清单）。两者均直接输出评价文本，不需要 JSON 格式。"
      />
      <a-form layout="vertical">
        <a-form-item label="子评测维度对比提示词">
          <a-textarea v-model:value="localCriteria.subtypeComparisonPrompt" :rows="8" />
          <a style="font-size: 12px;" @click="resetPrompt('subtypeComparison')">恢复默认</a>
        </a-form-item>
        <a-form-item label="总维度综合对比提示词">
          <a-radio-group v-model:value="comparisonPromptScene" style="margin-bottom: 8px;">
            <a-radio-button value="performance">模型表现对比</a-radio-button>
            <a-radio-button value="regression">回归测试验证</a-radio-button>
          </a-radio-group>
          <div style="color: #999; font-size: 12px; margin-bottom: 8px;">
            <template v-if="comparisonPromptScene === 'performance'">
              用于比较多个不同模型的表现，给出优势排名。
            </template>
            <template v-else>
              用于同一模型不同批次/不同时间点的测评结果对比，判断结果是否一致、差异体现在哪里。
            </template>
          </div>
          <a-textarea
            v-if="comparisonPromptScene === 'performance'"
            v-model:value="localCriteria.overallComparisonPrompt"
            :rows="8"
          />
          <a-textarea
            v-else
            v-model:value="localCriteria.overallComparisonPromptRegression"
            :rows="8"
          />
          <a style="font-size: 12px;" @click="resetPrompt('overallComparison')">恢复默认</a>
        </a-form-item>
        <div style="text-align: right;">
          <a-button type="primary" :loading="savingComparisonPrompt" @click="handleSaveComparisonPrompt">保存提示词</a-button>
        </div>
      </a-form>
    </a-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useConfigStore } from '@/stores/config.js'
import { message } from 'ant-design-vue'

const configStore = useConfigStore()
const savingPrompt = ref(false)
const savingReportPrompt = ref(false)
const savingComparisonPrompt = ref(false)
const comparisonPromptScene = ref('performance')

const DEFAULT_TEXT_JUDGE_PROMPT =
  '你是一个严格的评测打分专家。请结合“预期答案”和“实际答案”，从以下三个维度打分（0-1分，保留两位小数）：\n' +
  '1. accuracy（准确性）：实际答案的内容是否正确，与预期答案的事实一致性；\n' +
  '2. completeness（完整性）：实际答案是否覆盖了预期答案的所有要点；\n' +
  '3. relevance（相关性）：实际答案是否与问题相关，有没有答非所问或冗余内容；\n' +
  '请以JSON格式返回，不要包含其他解释：\n' +
  '```json\n{"accuracy": 0.85, "completeness": 0.70, "relevance": 0.90, "reason": "简要说明评分理由"}\n```\n\n' +
  '问题：{{question}}\n预期答案：\n{{expected}}\n实际答案：\n{{answer}}'

const DEFAULT_PPT_JUDGE_PROMPT =
  '你是一个严格的 PPT 内容评估专家，请对比“标准答案PPT”和“生成PPT”的文字内容，判断生成内容与标准答案的一致性、完整性和相关性。\n' +
  '请综合考虑关键信息是否准确、重要内容是否完整、是否存在无关或错误内容，并按 0-1 分评分。\n' +
  '请以JSON格式返回，不要包含其他解释：\n' +
  '```json\n{"score": 0.85, "reason": "简要说明评分理由"}\n```\n\n' +
  '标准答案PPT文字：\n{{expectedSlides}}\n\n生成PPT文字：\n{{generatedSlides}}'

const DEFAULT_HTML_JUDGE_PROMPT =
  '你是一个严格的 HTML 报告内容评估专家，请对比“标准答案报告正文”和“生成报告正文”，判断生成内容与标准答案的一致性、完整性和相关性。\n' +
  '请重点检查事实准确性、重要信息覆盖情况以及无关或错误内容，并按 0-1 分评分。\n' +
  '请以JSON格式返回，不要包含其他解释：\n' +
  '```json\n{"score": 0.85, "reason": "简要说明评分理由"}\n```\n\n' +
  '标准答案报告正文：\n{{expectedContent}}\n\n生成报告正文：\n{{generatedContent}}'

const DEFAULT_SUB_EVAL_REPORT_PROMPT =
  '你是一个专业的测评报告撰写专家。请根据以下子评测的测评结果，撰写一段简洁、客观的分析总结。\n' +
  '说明测评表现、主要优点和需要改进的方面，不要逐题复述。\n\n' +
  '子评测名称：{{subEvalName}}\n综合得分：{{accuracy}}\n题目及测评结果：\n{{questionList}}'

const DEFAULT_OVERALL_REPORT_PROMPT =
  '你是一个专业的测评报告撰写专家。请根据各子评测结果，对被测对象的整体表现进行综合评价。\n' +
  '总结整体能力、表现突出的方面和主要改进方向，语言简洁、客观，不要输出 JSON。\n\n' +
  '各子评测结果：\n{{subEvalSummaryList}}'

const DEFAULT_SUBTYPE_COMPARISON_PROMPT =
  '你是一个专业的模型测评对比分析专家。请针对“{{subtypeName}}”维度，比较各模型的测评结果。\n' +
  '指出各模型的优势与差异，结合得分和评价给出客观分析，不要输出 JSON。\n\n' +
  '参与对比的模型结果：\n{{reportList}}'

const DEFAULT_OVERALL_COMPARISON_PROMPT =
  '你是一个严谨的模型能力评估专家。以下是多个模型在各子评测维度下的对比分析，以及各模型的综合得分。\n' +
  '请综合分析各模型的整体能力、优势排名及适用场景，给出客观、简洁的总结，不要输出 JSON。\n\n' +
  '各子评测维度对比：\n{{subtypeComparisonList}}\n\n各模型综合得分：\n{{reportOverallList}}'

const DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION =
  '你是一个严谨的模型回归测试分析专家。以下是同一模型在不同批次或不同时间点的测评结果。\n' +
  '请比较各子评测维度的表现与综合得分，判断结果是否稳定，指出显著变化及可能的能力退化或提升，不要输出 JSON。\n\n' +
  '各子评测维度对比：\n{{subtypeComparisonList}}\n\n各批次综合得分：\n{{reportOverallList}}'

const localCriteria = reactive({
  textJudgePrompt: '',
  pptJudgePrompt: '',
  htmlJudgePrompt: '',
  subEvalReportPrompt: '',
  overallReportPrompt: '',
  subtypeComparisonPrompt: '',
  overallComparisonPrompt: '',
  overallComparisonPromptRegression: ''
})

onMounted(async () => {
  await configStore.fetchCriteria()
  const criteria = configStore.criteria || {}
  localCriteria.textJudgePrompt = criteria.textJudgePrompt || DEFAULT_TEXT_JUDGE_PROMPT
  localCriteria.pptJudgePrompt = criteria.pptJudgePrompt || DEFAULT_PPT_JUDGE_PROMPT
  localCriteria.htmlJudgePrompt = criteria.htmlJudgePrompt || DEFAULT_HTML_JUDGE_PROMPT
  localCriteria.subEvalReportPrompt = criteria.subEvalReportPrompt || DEFAULT_SUB_EVAL_REPORT_PROMPT
  localCriteria.overallReportPrompt = criteria.overallReportPrompt || DEFAULT_OVERALL_REPORT_PROMPT
  localCriteria.subtypeComparisonPrompt = criteria.subtypeComparisonPrompt || DEFAULT_SUBTYPE_COMPARISON_PROMPT
  localCriteria.overallComparisonPrompt = criteria.overallComparisonPrompt || DEFAULT_OVERALL_COMPARISON_PROMPT
  localCriteria.overallComparisonPromptRegression = criteria.overallComparisonPromptRegression || DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION
})

function resetPrompt(type) {
  if (type === 'text') localCriteria.textJudgePrompt = DEFAULT_TEXT_JUDGE_PROMPT
  else if (type === 'ppt') localCriteria.pptJudgePrompt = DEFAULT_PPT_JUDGE_PROMPT
  else if (type === 'html') localCriteria.htmlJudgePrompt = DEFAULT_HTML_JUDGE_PROMPT
  else if (type === 'subEvalReport') localCriteria.subEvalReportPrompt = DEFAULT_SUB_EVAL_REPORT_PROMPT
  else if (type === 'overallReport') localCriteria.overallReportPrompt = DEFAULT_OVERALL_REPORT_PROMPT
  else if (type === 'subtypeComparison') localCriteria.subtypeComparisonPrompt = DEFAULT_SUBTYPE_COMPARISON_PROMPT
  else if (type === 'overallComparison' && comparisonPromptScene.value === 'regression') {
    localCriteria.overallComparisonPromptRegression = DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION
  } else if (type === 'overallComparison') {
    localCriteria.overallComparisonPrompt = DEFAULT_OVERALL_COMPARISON_PROMPT
  }
}

async function handleSavePrompt() {
  savingPrompt.value = true
  try {
    await configStore.updateCriteria({
      textJudgePrompt: localCriteria.textJudgePrompt,
      pptJudgePrompt: localCriteria.pptJudgePrompt,
      htmlJudgePrompt: localCriteria.htmlJudgePrompt
    })
    message.success('评分提示词已保存')
  } finally {
    savingPrompt.value = false
  }
}

async function handleSaveReportPrompt() {
  savingReportPrompt.value = true
  try {
    await configStore.updateCriteria({
      subEvalReportPrompt: localCriteria.subEvalReportPrompt,
      overallReportPrompt: localCriteria.overallReportPrompt
    })
    message.success('报告提示词已保存')
  } finally {
    savingReportPrompt.value = false
  }
}

async function handleSaveComparisonPrompt() {
  savingComparisonPrompt.value = true
  try {
    await configStore.updateCriteria({
      subtypeComparisonPrompt: localCriteria.subtypeComparisonPrompt,
      overallComparisonPrompt: localCriteria.overallComparisonPrompt,
      overallComparisonPromptRegression: localCriteria.overallComparisonPromptRegression
    })
    message.success('对比提示词已保存')
  } finally {
    savingComparisonPrompt.value = false
  }
}
</script>
