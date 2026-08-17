# 安全说明（Security）

## 报告漏洞

本仓库是**个人开发的社区第三方客户端**（远程控制 DSH 的 App 与插件）。安全相关漏洞请**私有**报告，勿公开披露细节：

- **首选**：GitHub 私有安全通告（Security → Advisories → New draft security advisory），或对该仓库开一个标记为敏感、不公开细节的 Issue。
- 若需邮件：请在 GitHub 资料中通过私有通告交流，避免把敏感细节写进公共渠道。

请包含：影响版本、复现步骤、预期行为 vs 实际行为、缓解建议。

## 本项目重点关注的安全面

- **配对与会话安全**（`dsh-remote-access`）：`pair/*` 豁免面收敛、配对码一次性/限次/过期、
  `verify` 常量时间比较、通道 token 的 /api 全量 Bearer 门禁、公网域名白名单（trustedHosts）校验。
- **远程访问边界**：仅向已配对设备签发 token；`pair/respond`、`pair/list`、`pair/revoke`、
  `pair/code/generate|current` 仅本机/带 token 可达。
- **App 端**：凭证加密存储（AndroidKeyStore）、连接 MAC 校验、高危操作二次确认。

任何改动涉及上述面时，请同时跑 `dsh-remote-access` 的冒烟测试（`npm test`）。
