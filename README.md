# EvalForge

EvalForge 是一个面向 AI Agent 与大语言模型的评测工作台，将评测数据、模型运行、评分和结果复核集中在一个应用中。

## 功能概览

- 管理数据集和评测记录。
- 配置模型组并运行模型评测。
- 使用可配置的评测标准评估 Agent 会话。
- 查看评分、对比评测结果并检查报告。
- 连接已配置的 Agent 平台和模型服务。

仓库包含 Vue 3 前端和 Java 17 / Spring Boot 后端。

## 项目结构

- `src/`：Vue 前端源码。
- `qatools-backend/src/`：Java 后端源码。
- `qatools-backend/init.sql`：应用自有数据表的还原版开发 schema。

## 运行前端

需要 Node.js 18 或更高版本。

```sh
npm ci
npm run dev
```

Vite 默认在 `http://localhost:5173` 提供服务，默认使用浏览器端 Mock 数据。连接后端时，复制 `.env.example` 为 `.env.local`，并设置：

```dotenv
VITE_USE_MOCK=false
VITE_API_BASE_URL=http://localhost:8081
```

构建和预览生产版本：

```sh
npm run build
npm run preview
```

## 运行后端

后端依赖、环境变量、数据库初始化和 Docker 说明见 [qatools-backend/README.md](qatools-backend/README.md)。

`qatools-backend/init.sql` 根据现有 Java SQL 语句和行映射还原，是开发环境的基础 schema，不是原始生产数据库导出。它只创建 EvalForge 自有数据表；Agent 平台的外部数据表由对应平台提供。真实服务地址和凭据请通过本地环境变量配置，切勿提交密钥或私有服务地址。

## 构建前端镜像

```sh
docker build -t evalforge-frontend .
docker run --rm -p 8080:80 evalforge-frontend
```

然后访问 `http://localhost:8080`。

## 安全与提交

本机环境文件、依赖目录、构建产物、IDE 文件和运行数据已排除在版本控制之外。提交前请检查变更，不要提交密码、API 密钥、授权头、内部地址或用户数据。
