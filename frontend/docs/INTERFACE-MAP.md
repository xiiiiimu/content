# 页面 → 后端接口对应表

| 页面 | 方法 / 路径 | 主要字段 | 前端处理 |
| --- | --- | --- | --- |
| `/hot-topics` | `GET /api/topics/trending?window&metric` | `topic`, `score` | 需 Bearer；展示 score；无参与者 |
| `/hot-topics/:topic` | `GET /api/topics/{topic}/posts?limit` | `View` | limit≤50；authorId |
| `/hot-topics/:topic/:post` | `GET /api/posts/{id}` | `View` | 硬刷新；互动/媒体 |
| `/posts/:id` | 同上 | `View` | Feed 入口详情 |
| `/feed` | `GET /api/feed/following?limit&cursor` | `items`, `nextCursor`, `pageState` | 透传 cursor；UNKNOWN≠结束 |
| 关注 | `PUT/DELETE /api/follows/{author}` | `following` | 详情内 |
| 点赞 | `PUT/DELETE /api/posts/{post}/likes` | `liked`, `likeCount` | 以响应为准 |
| 评论 | `POST /api/posts/{post}/comments` | `commentCount` | 无列表 |
| 媒体 | `POST /api/posts/{post}/media/sign` | `url`, `expiresAtMs` | 过期重载 |
| `/ops/risks` | `GET /api/trendguard/risks` | Risk 字段 | ops-only；keyset |

错误体统一：`{"code":"..."}`。开发代理：`/api` → `127.0.0.1:28080`。
