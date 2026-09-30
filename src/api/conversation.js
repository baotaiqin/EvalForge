import { registerMock } from './request.js'

// 模拟模型回答
const modelAnswers = [
  '根据您的问题，我来为您详细解答。这个问题涉及到多个方面，首先需要了解基本概念，然后结合实际场景进行分析。总体来说，建议您从以下几个维度思考这个问题....',
  '这是一个很好的问题！从技术角度来看，我认为关键在于理解底层原理。让我们分步骤为您说明：第一步是明确需求边界，第二步是选择合适的技术方案，第三步是进行验证和优化....',
  '感谢您的提问。关于这个话题，业界有多种不同的看法。最主流的观点认为应该采用渐进式的方法来解决问题，具体操作步骤如下：1）需求分析 2）方案设计 3）原型验证 4）迭代优化....',
  '这个问题的答案取决于具体的应用场景和需求。在大多数情况下，推荐的做法是先进行充分的调研，然后制定详细的实施计划。以下是我的详细建议....',
  '让我从多个角度来分析这个问题。首先从理论层面来看，这涉及到核心概念的理解；其次从实践层面来看，需要考虑可行性和效率；最后从成本角度，还需要权衡投入产出比....'
]

function getRandomAnswer() {
  return modelAnswers[Math.floor(Math.random() * modelAnswers.length)]
}

// 多模型对话
registerMock('POST', '/api/conversation/models', ({ data }) => {
  const { question, modelIds } = data
  const responses = modelIds.map((modelId, index) => ({
    modelId,
    modelName: `模型-${modelId.replace('model-', '')}`,
    answer: getRandomAnswer() + `\n\n[以上回答由模型 ${modelId} 生成，针对问题："${question.slice(0, 30)}..."]`,
    responseTime: Math.floor(500 + Math.random() * 3000)
  }))
  return { code: 0, data: { responses } }
})

// 重新回答（仅模型）
registerMock('POST', '/api/conversation/regenerate', ({ data }) => {
  const { question, targetId } = data
  return {
    code: 0,
    data: {
      answer: getRandomAnswer() + `\n\n[重新生成的回答，针对问题："${question.slice(0, 30)}..."]`,
      responseTime: Math.floor(500 + Math.random() * 3000),
      dialogId: data.dialogId || 'mock-dialog-regen-' + Date.now(),
      chatToken: data.chatToken || 'mock-token-regen-' + Math.random().toString(36).slice(2, 10)
    }
  }
})
