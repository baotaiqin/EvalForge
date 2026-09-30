import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

// A4 纸张尺寸（单位 mm），供按比例分页贴图使用
const A4_WIDTH_MM = 210
const A4_HEIGHT_MM = 297

/**
 * 将指定 DOM 容器整体截图后导出为 PDF：模型测评报告/报告对比这类内容以大段中文叙述
 * 为主，jsPDF 原生文本渲染不支持中文字体，改用“截图整个容器再按 A4 尺寸分页贴图”的方案，
 * 无需内嵌字体资源即可完整保留现有的排版与配色（浅蓝/浅黄底色卡片等）。
 *
 * @param {HTMLElement} container 要截图的 DOM 容器（通常是弹窗内容区域的 ref）
 * @param {string} fileName 导出的 PDF 文件名（含 .pdf 后缀）
 */
export async function exportElementToPdf(container, fileName) {
  if (!container) return

  // 弹窗内容区域通常带 overflow-y:auto + 固定高度用于在屏幕上滚动展示。
  // 截图前需要临时展开为真实完整高度，否则只能截到当前可视区域，滚动条以下内容会丢失
  const originalStyle = {
    height: container.style.height,
    maxHeight: container.style.maxHeight,
    overflow: container.style.overflow,
    overflowY: container.style.overflowY
  }
  container.style.height = 'auto'
  container.style.maxHeight = 'none'
  container.style.overflow = 'visible'
  container.style.overflowY = 'visible'

  try {
    const canvas = await html2canvas(container, {
      scale: 2, // 提升截图清晰度，避免文字在 PDF 里模糊
      useCORS: true,
      backgroundColor: '#ffffff'
    })

    const imgData = canvas.toDataURL('image/jpeg', 0.95)
    const pdf = new jsPDF('p', 'mm', 'a4')

    // 按画布实际宽高比换算出等比缩放后贴到 A4 宽度时对应的总高度（mm），
    // 若总高度超过单页高度则按页高逐页切割绘制，模拟多页 PDF 效果
    const imgWidthMm = A4_WIDTH_MM
    const imgHeightMm = (canvas.height * imgWidthMm) / canvas.width

    let remainingHeightMm = imgHeightMm
    let renderedHeightPx = 0
    const pageHeightPx = (A4_HEIGHT_MM * canvas.width) / A4_WIDTH_MM

    let isFirstPage = true
    while (remainingHeightMm > 0) {
      if (!isFirstPage) {
        pdf.addPage()
      }
      isFirstPage = false

      const sliceHeightPx = Math.min(pageHeightPx, canvas.height - renderedHeightPx)
      const pageCanvas = document.createElement('canvas')
      pageCanvas.width = canvas.width
      pageCanvas.height = sliceHeightPx
      const ctx = pageCanvas.getContext('2d')
      ctx.drawImage(canvas, 0, renderedHeightPx, canvas.width, sliceHeightPx, 0, 0, canvas.width, sliceHeightPx)

      const pageImgData = pageCanvas.toDataURL('image/jpeg', 0.95)
      const pageImgHeightMm = (sliceHeightPx * imgWidthMm) / canvas.width
      pdf.addImage(pageImgData, 'JPEG', 0, 0, imgWidthMm, pageImgHeightMm)

      renderedHeightPx += sliceHeightPx
      remainingHeightMm -= pageImgHeightMm
    }

    pdf.save(fileName)
  } finally {
    container.style.height = originalStyle.height
    container.style.maxHeight = originalStyle.maxHeight
    container.style.overflow = originalStyle.overflow
    container.style.overflowY = originalStyle.overflowY
  }
}
