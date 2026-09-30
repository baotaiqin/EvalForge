# QATools

QATools 是一个 AI Agent 与大模型质量评测项目，包含 Vue 前端和 Java 后端源码。

## 项目结构

- 前端位于仓库根目录：Vue 3、Vite、Pinia、Ant Design Vue。
- 后端源码位于 `qatools-backend/src`：Java / Spring 服务代码。
- 后端运行数据、截图、上传文件和编译输出不纳入版本控制。

## 前端运行

需要 Node.js 18 或更高版本。

```sh
npm ci
npm run dev
```

Vite 默认监听 `http://localhost:5173`。默认使用内存 Mock 数据。构建和预览：

```sh
npm run build
npm run preview
```

复制 `.env.example` 为 `.env.local` 可调整前端模式和 API 地址。`.env.local` 不要提交到 Git。

- `VITE_USE_MOCK=true`：使用浏览器端演示数据。
- `VITE_USE_MOCK=false`：通过 `VITE_API_BASE_URL` 连接后端。
- 演示环境地址使用 `example.invalid` 保留域名；真实服务地址和凭据应由使用者自行配置。

## 后端配置

后端源码在 `qatools-backend/src`。敏感配置使用环境变量注入；`application.properties` 中仅保留本机和演示默认值，不包含工作区中的内网地址、账号凭据或授权令牌。连接真实数据库、MinIO 或 Agent 环境前，请自行设置对应环境变量。

**注意：当前工作区提供的后端 `pom.xml`、`Dockerfile` 和 `init.sql` 是 TODO 占位文件，Maven 依赖、后端镜像构建和数据库初始化定义不完整。因此本仓库包含后端源码，但暂不能据此直接构建、启动完整后端。**我没有猜测或补写缺失依赖和数据库结构。

## Docker

前端目录中的 `Dockerfile` 可构建 Nginx 静态站点镜像：

```sh
docker build -t qatools-app .
docker run --rm -p 8080:80 qatools-app
```

访问 `http://localhost:8080`。工作区根目录的 Compose 文件是占位内容，因此没有纳入仓库。

## Git 与隐私

`.gitignore` 排除依赖、构建输出、本机环境文件、后端运行数据、截图和 IDE 文件。提交前仍请检查变更，勿提交真实凭据、访问令牌、内网地址或用户数据。