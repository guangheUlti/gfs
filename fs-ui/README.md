<div align="center">

<img alt="GFS Logo" src="../.images/logo.png" width="100"/>

# GFS Frontend

### 现代化文件管理网盘系统 - 前端

基于 React 19 + TypeScript + Vite 的企业级文件管理网盘系统前端，与 [GFS](https://github.com/guangheUlti/gfs) 后端配套使用。

<img src="https://img.shields.io/badge/React-19-blue.svg" alt="React">
<img src="https://img.shields.io/badge/TypeScript-5.x-blue.svg" alt="TypeScript">
<img src="https://img.shields.io/badge/Vite-latest-blue.svg" alt="Vite">

</div>

---

## 技术栈

| 技术              | 说明              | 版本   |
| ----------------- | ----------------- | ------ |
| React             | UI 框架           | 19.x   |
| TypeScript        | 类型安全          | 5.9.x  |
| Vite              | 构建工具          | 6.x    |
| React Router      | 路由管理          | 7.x    |
| TanStack Query    | 服务端状态 / 请求 | 5.x    |
| Zustand           | 客户端状态管理    | 5.x    |
| shadcn/ui         | UI 组件库         | Latest |
| Tailwind CSS      | 样式框架          | 4.x    |
| Axios             | HTTP 客户端       | 1.x    |

## 快速开始

### 环境要求

- Node.js `>= 20.19`
- pnpm `>= 9`（仓库 lockfile 为 pnpm v9）

### 运行

```bash
# 在 fs-ui/ 目录下安装依赖
pnpm install

# 启动开发服务器（默认 http://localhost:5173，/apis 代理到后端 80）
pnpm dev

# 构建生产版本
pnpm build
```

后端地址通过 `.env.development` 中的 `VITE_API_BASE_URL` 配置，默认 `http://localhost:80`。

## 项目结构

```
fs-ui/
├── public/              # 静态资源
├── src/
│   ├── api/            # API 接口定义
│   ├── components/     # 公共组件（layout / ui 组件库）
│   ├── contexts/       # React Context
│   ├── hooks/          # 自定义 Hooks
│   ├── lib/            # 工具库
│   ├── locales/        # 国际化文案（中/英）
│   ├── pages/          # 页面组件
│   ├── router/         # 路由配置
│   ├── store/          # Zustand 状态管理
│   ├── styles/         # 全局样式
│   ├── App.tsx         # 根组件
│   └── main.tsx        # 应用入口
└── vite.config.ts      # Vite 配置
```

## 主要功能

- 📂 文件管理：上传、下载、预览、重命名、移动、删除
- 🔍 文件搜索 / ⭐ 收藏 / 🗑️ 回收站
- 🔗 文件分享：链接 + 提取码 + 有效期
- 📊 存储管理：多存储平台配置
- 📈 传输管理：上传下载任务管理
- 🎨 主题切换：亮色/暗色模式
- 🌐 国际化：中英文双语

---

<div align="center">

Made with ❤️ by [guangheUlti](https://github.com/guangheUlti/)

</div>
