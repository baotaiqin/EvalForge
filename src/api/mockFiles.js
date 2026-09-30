import { registerMock } from './request.js'

registerMock('GET', '/api/files/preview', ({ params }) => {
  const url = String(params?.url || '')
  const fileName = url.split(/[/?#]/).filter(Boolean).pop() || '演示文件'
  const extension = fileName.split('.').pop()?.toLowerCase()
  if (['svg', 'png', 'jpg', 'jpeg', 'gif', 'webp'].includes(extension)) {
    return { code: 0, data: { isImage: true, content: url, fileName } }
  }
  return {
    code: 0,
    data: {
      isText: true,
      content: `本地演示模式没有连接文件存储服务。\n文件名称：${fileName}\n资源地址：${url}`,
      fileName
    }
  }
})
