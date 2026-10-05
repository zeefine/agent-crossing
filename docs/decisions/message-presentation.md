# 消息正文展示与阅读位置

日期：2026-10-05

## 展示约定

- 用户消息保持纯文本，右对齐气泡；Agent 回复使用开放式 Markdown 正文。
- 使用 react-markdown 10 / remark-gfm 4，支持 CommonMark 和 GFM 表格。代码块展示语言标签并支持复制，不执行代码。
- 复制消息得到原始 Markdown；复制代码得到代码块正文。剪贴板权限失败时显示可手动复制的提示。
- 保留一个 Agent 显示名称；完整时间放在次要时间标签的 title 中。复制入口在桌面悬停、键盘聚焦和触屏环境可用。
- 消息状态直接取现有接口的 status：streaming 为生成中，canceled 为已停止，failed 为生成失败；保留已有正文，不根据文本猜测终态。
- 用户上翻后暂停跟随，提供“回到最新消息”；主动发送和切换会话恢复跟随。视口尺寸变化仅在跟随开启时滚到底部。
- 未改变消息版本合并、取消协议及后端存储。非标准 Markdown（如 `---##`）保持原意，不做容易误改代码的正则修复。

## 安全边界

Agent 输出是不可信文本。正文不使用 dangerouslySetInnerHTML，不启用原始 HTML 解析；保持渲染库默认 URL 安全过滤。外链携带 noopener / noreferrer，Markdown 图片渲染为用户主动点击的链接，避免自动请求追踪图片。未引入远程语法高亮或脚本。

## 验证与依赖审计

Playwright 使用模拟 HTTP / WebSocket，覆盖排版、复制成功/失败、HTML 与危险链接、图片请求、未闭合代码块、流式终态、阅读位置，以及深浅主题、窄屏和 axe 检查；不会调用真实 Agent。

2026-10-05 使用官方 npm 审计接口检查运行时依赖，未报告新增 Markdown 依赖的已知漏洞；现有 Next.js 14.2.35 / PostCSS 依赖仍被报告为 critical / high。当前镜像不提供审计接口，需要 `npm audit --omit=dev --registry=https://registry.npmjs.org`。

这些既有依赖风险未在本次消息 UI 改造中自动升级：审计建议包含 Next.js 跨主版本迁移，需要独立兼容性验证。Windows 专属公告不适用于当前 macOS 环境，但应用使用 App Router 和 rewrites，其他公告不能仅据此判为不可达；部署到公网前必须完成逐项排查和修复。建议复核日期：2026-10-12（仅记录，不创建自动提醒）。

参考：[react-markdown 官方文档](https://github.com/remarkjs/react-markdown)、[remark-gfm 官方文档](https://github.com/remarkjs/remark-gfm)。
