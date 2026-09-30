# QATools 前端

QATools 是一个基于 Vue 3 的质量评测工具前端，提供配置管理、对话、数据集、Agent 评测和模型评测等页面。项目带有浏览器内存 Mock 数据，可在没有后端服务时启动演示。

## 技术栈

- Vue 3、Vite、Vue Router、Pinia
- Ant Design Vue
- Axios
- XLSX、Marked、DOMPurify

## 环境要求

- Node.js 18 或更高版本
- npm（仓库包含 `package-lock.json`）

## 本地运行

```sh
npm ci
npm run dev
```

Vite 默认地址为 `http://localhost:5173`。本地构建和预览：

```sh
npm run build
npm run preview
```

## 环境变量与数据模式

复制 `.env.example` 为 `.env.local` 后按需编辑。`.env.local` 会被 Git 忽略，不要把真实凭据提交到仓库。

```dotenv
VITE_USE_MOCK=true
# VITE_API_BASE_URL=http://localhost:8081
```

- `VITE_USE_MOCK=true`：使用内存 Mock 数据（默认演示模式）。刷新页面会恢复初始演示数据。
- `VITE_USE_MOCK=false`：连接真实后端，并通过 `VITE_API_BASE_URL` 指定 API 地址。环境变量由 Vite 在启动或构建时读取。

演示数据里的环境主机名使用 `example.invalid` 保留域名，不会连接真实环境。配置真实环境前，请在应用的配置页面填写你自己的服务地址和凭据，并通过本机未跟踪的环境文件提供构建配置。

## 功能说明

- Mock 模式可用于浏览主要页面和体验基础交互。
- 数据集演示解析支持 JSON、JSONL/NDJSON 和 Excel；DOC/ZIP 等文件解析需要后端服务。
- 真实后端需实现 `src/api` 中调用的 `/api` 接口。对话和评测的流式响应使用 SSE。
- `Dockerfile` 可构建 Nginx 静态站点镜像；默认使用演示模式：

```sh
docker build -t qatools-app .
docker run --rm -p 8080:80 qatools-app
```

访问 `http://localhost:8080`。

## Git 提交提示

仓库已忽略依赖目录、构建产物、本地环境文件、IDE 配置和日志。提交前请检查变更，确认没有加入 `.env.local`、访问令牌、真实主机地址或用户数据。