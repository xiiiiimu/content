# Content 前端

Content 平台自有 Web UI。布局/配色参考 [Elk](https://github.com/elk-zone/elk)（见 `NOTICE` / `LICENSE.elk`），**不**内嵌 Mastodon 登录或 Herd 客户端。

## 要求

- Windows 10+（本机 PowerShell；**不依赖 WSL / 原 Elk 目录**）
- Node.js 20+（已在 Node 22 验证）
- pnpm 9+（推荐 `corepack enable`）

## 安装

```powershell
cd E:\content\frontend
pnpm install
```

## 开发（端口 5314）

后端默认 `http://127.0.0.1:28080`。开发时通过 Nuxt Nitro 将 `/api` 代理到该地址。

```powershell
# 另开终端启动 Content API（feed 启用）
# 然后：
pnpm dev
```

浏览器打开 `http://127.0.0.1:5314`。

### 开发身份

后端无登录 HTTP。使用仓库脚本本机 mint（**密钥勿放入 frontend**）：

```powershell
# 在仓库根，按 infra 文档设置 CONTENT_IDENTITY_KEY 后：
.\infra\docker\mint-identity.ps1 -UserId 2001
```

将输出的 token（不含 `Bearer ` 前缀）粘贴到左侧「开发身份」。运营页需 `TRENDGUARD_OPS` 角色 token。

过期后接口返回 401；清除并重新 mint。

## 生产构建与启动

```powershell
pnpm build
pnpm start
```

生产可设置：

```powershell
$env:NUXT_PUBLIC_CONTENT_API_BASE = "https://your-api.example"
```

参见 `.env.example`。

## 测试

```powershell
pnpm test
```

## 页面

| 路径 | 能力 |
| --- | --- |
| `/hot-topics` | 热点列表 |
| `/hot-topics/:topic` | 话题帖 |
| `/hot-topics/:topic/:post` | 帖子详情 |
| `/feed` | 关注 Feed |
| `/ops/risks` | TrendGuard（ops） |
| `/docs/gaps` | 缺口说明 |

接口表：`docs/INTERFACE-MAP.md`。缺口：`docs/CAPABILITY-GAPS.md`。
