import { jsPDF } from 'jspdf'
import * as XLSX from 'xlsx'

/**
 * 导出评测报告为 Excel
 */
export function exportToExcel(detail, summary) {
  const wb = XLSX.utils.book_new()

  // Sheet 1: 基本信息
  const infoData = [
    ['评测报告'],
    [],
    ['评测名称', detail.name],
    ['评测类型', detail.type === 'agent' ? 'Agent' : '模型'],
    ['状态', detail.status],
    ['开始时间', detail.startTime],
    ['结束时间', detail.endTime || '--'],
    ['备注', detail.remark || '--'],
    [],
    ['被评测对象'],
    ...detail.targets.map(t => ['', t.label]),
    [],
    ['数据集'],
    ...(detail.datasetNames || []).map(n => ['', n])
  ]
  const infoSheet = XLSX.utils.aoa_to_sheet(infoData)
  XLSX.utils.book_append_sheet(wb, infoSheet, '基本信息')

  // Sheet 2: 评测结果明细
  const headers = ['序号', '问题', '是否有预期结果', '预期答案']
  detail.targets.forEach(t => {
    headers.push(`${t.label} - 输出`)
    headers.push(`${t.label} - 综合得分`)
    headers.push(`${t.label} - 准确性`)
    headers.push(`${t.label} - 完整性`)
    headers.push(`${t.label} - 相关性`)
  })

  const rows = [headers]
  ;(detail.results || []).forEach((result, index) => {
    const row = [
      index + 1,
      result.question,
      result.hasExpected ? '是' : '否',
      result.expectedAnswer || '--'
    ]
    detail.targets.forEach(t => {
      const output = result.outputs?.[t.id]
      const scores = result.scores?.[t.id]
      const displayScores = scores?.manual || scores?.auto || {}
      row.push(output?.answer || '--')
      row.push(scores?.final ? (scores.final * 100).toFixed(1) + '%' : '--')
      row.push(displayScores.accuracy ? (displayScores.accuracy * 100).toFixed(1) + '%' : '--')
      row.push(displayScores.completeness ? (displayScores.completeness * 100).toFixed(1) + '%' : '--')
      row.push(displayScores.relevance ? (displayScores.relevance * 100).toFixed(1) + '%' : '--')
    })
    rows.push(row)
  })

  const resultSheet = XLSX.utils.aoa_to_sheet(rows)
  XLSX.utils.book_append_sheet(wb, resultSheet, '评测结果明细')

  // Sheet 3: 汇总统计
  const summaryData = [
    ['汇总统计'],
    [],
    ['被评测对象', '综合得分', '准确率']
  ]
  Object.values(summary).forEach(s => {
  summaryData.push([s.label, s.avgScore + '分', (s.accuracy * 100).toFixed(1) + '%'])
  })

  const summarySheet = XLSX.utils.aoa_to_sheet(summaryData)
  XLSX.utils.book_append_sheet(wb, summarySheet, '汇总统计')

  XLSX.writeFile(wb, `评测报告_${detail.name}.xlsx`)
}

/**
 * 导出评测报告为 PDF（简版，使用 jsPDF 基础文本）
 */
export function exportToPDF(detail, summary) {
  const doc = new jsPDF()

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(16)
  doc.text('Evaluation Report', 20, 20)

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(10)

  let y = 35
  doc.text(`Name: ${detail.name}`, 20, y); y += 7
  doc.text(`Type: ${detail.type === 'agent' ? 'Agent' : 'Model'}`, 20, y); y += 7
  doc.text(`Status: ${detail.status}`, 20, y); y += 7
  doc.text(`Start: ${detail.startTime}`, 20, y); y += 7
  doc.text(`End: ${detail.endTime || '--'}`, 20, y); y += 12

  doc.setFont('helvetica', 'bold')
  doc.text('Summary:', 20, y); y += 7
  doc.setFont('helvetica', 'normal')

  Object.values(summary).forEach(s => {
    doc.text(`${s.label}: Score ${s.avgScore}, Accuracy ${(s.accuracy * 100).toFixed(1)}%`, 25, y)
    y += 7
  })

  y += 5
  doc.setFont('helvetica', 'bold')
  doc.text('Results:', 20, y); y += 7
  doc.setFont('helvetica', 'normal')

  ;(detail.results || []).slice(0, 20).forEach((result, index) => {
    if (y > 270) {
      doc.addPage()
      y = 20
    }
    doc.text(`${index + 1}. ${result.question.substring(0, 80)}`, 25, y)
    y += 6
    detail.targets.forEach(t => {
      const answer = result.outputs?.[t.id]?.answer || '--'
      const score = result.scores?.[t.id]?.final
      doc.text(`  ${t.label}: ${answer.substring(0, 60)}... (${score ? (score * 100).toFixed(1) + '%' : '--'})`, 30, y)
      y += 5
    })
    y += 3
  })

  doc.save(`评测报告_${detail.name}.pdf`)
}
