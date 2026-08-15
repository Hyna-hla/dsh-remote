# SKIN 主题包

DSH Remote 可导入主题包（zip：`theme.json` + `preview.png`），格式见 [docs/theme-package-format.md](../docs/theme-package-format.md)。

- `deeplook.dshTheme.zip` — **DeepLook**：DeepSeek 移动端 1:1 浅色（`#f8f8f8` 背景 + 纯白分组卡片 + 品牌蓝 `#4D6BFE`）
- `deeplook-dark.dshTheme.zip` — **DeepLook 深色**：规范 7.3 预留深色方案（`#0d1117` 底 + `#161b22` 卡片，品牌蓝不变）

DeepLook 同时是 App **内置主题**（`ThemeRegistry.kt` 的 `deeplook` / `deeplook-dark`，带专属 `DEEPLOOK` 样式分支：iOS 分组列表圆角 + 深蓝黑选中态），内置与导入包配色一致。

设计来源：`E:\AI搓的小东西\design\dsh-mobile-ui-spec.md` + `dsh-mobile-ui-prototype.html`。
生成管线：`E:\AI搓的小东西\SKIN\theme-packs\`（themes-data.mjs → build-theme-json.mjs → build-packs.ps1）。
