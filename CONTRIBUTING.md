# Contributing

感谢你的关注！本仓库是一个**个人开发的社区第三方客户端**（DSH Remote App + PC 端 dsh-remote-access 插件），欢迎以 Issue / PR 方式参与。

## 目录速览

- `app/` — Android App（Kotlin + Compose）
- `dsh-remote-access/` — PC 端 DSH 插件（`src/` 为 TS 源码，`lib/` 为编译产物 + 手写声明）
- `docs/` — 设计/协议/教程文档
- `scripts/` — 运维脚本（git-sync 等）

## 提交前

- **插件**（`dsh-remote-access/`）：改 `src/*.ts`，不要直接改 `lib/*.js`（会被构建覆盖）。
  ```bash
  cd dsh-remote-access
  npm install          # 安装 typescript + @types/node
  npm run typecheck    # tsc --noEmit
  npm run build        # src → lib
  npm test             # 冒烟（当前 12/12）
  npm pack             # prepack 会自动 typecheck+build+test，并产出 tgz
  ```
- **App**（`app/`）：`./gradlew assembleDebug` 至少能过编译。
- 提交信息遵循 `type(scope): 摘要`（feat/fix/docs/chore…），中文摘要亦可。
- CI（`.github/workflows/ci.yml`）会在 push/PR 上跑插件与 Android 构建与测试，请确保通过。

## Git 同步（可选）

本机开发常用 `scripts/git-sync.ps1`（先拉取再提交，冲突本地解决）：
```powershell
.\scripts\git-sync.ps1 -Once -CommitMessage "feat: 说明"
```

## 行为准则

友善交流；本仓库 MIT 许可、完全开源免费，勿以此牟利。
