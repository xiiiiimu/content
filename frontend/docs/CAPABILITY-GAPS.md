# 后端能力缺口（前端不伪造）

| 缺口 | 影响 |
| --- | --- |
| 登录 / 注册 HTTP | 开发依赖 `mint-identity.ps1` 注入 Bearer |
| 创建草稿 | 无完整发帖编辑器 |
| 评论列表 | 仅能发评与显示计数 |
| 喜欢列表 | 无独立喜欢页 |
| 话题游标分页 | 话题帖最多 50 |
| 用户目录 / 搜索 | 关注需已知 authorId |
| Feed streaming | 仅拉取分页 |
| 封面 sign HTTP | 封面能力未接独立入口 |
| 热点参与者 / 头像 / 显示名 | 列表仅 topic+score |

身份签名密钥（`CONTENT_IDENTITY_KEY` / `CONTENT_CURSOR_KEY`）不得进入前端或公开配置。
