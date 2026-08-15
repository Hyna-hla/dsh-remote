# 可拓展主题包格式（v1.0.15+）

DSH Remote 支持自定义主题：**单个 zip 压缩包 = 一套主题**，导入后即时生效（热加载），
同名主题再次导入 = 覆盖更新（热替换），无需重启 App、无需重新安装。

## 打包方式

把以下文件放进一个 zip（可用任意解压工具或 Windows 右键 → 压缩为 zip）：

    theme.json      # 必填：主题定义（UTF-8）
    preview.png     # 可选：预览图（≤2MB，设置页列表展示缩略图）
    README.md       # 可选：主题说明（≤100KB）

zip 里只认根目录的这三个文件名，其他内容忽略。建议文件命名 `主题名.dshTheme.zip`。

## theme.json 格式

    {
      "id": "aurora",
      "name": "极光紫",
      "light": false,
      "version": "1.1",
      "colors": {
        "background":   "#14101F",
        "surface":      "#1E1830",
        "surfaceHigh":  "#2A2240",
        "brand":        "#9D7BFF",
        "brandSoft":    "#B79CFF",
        "textPrimary":  "#F0EAFB",
        "textSecondary":"#B4A6D4",
        "border":       "#3A3058",
        "success":      "#4FC3A1",
        "warn":         "#E8C46E",
        "error":        "#E87777"
      }
    }

- id：仅字母/数字/下划线/连字符，≤48 字符；**与内置主题（blue/black/warm）冲突会被拒绝**
- name：显示名（必填）
- light：true = 浅色主题（状态栏自动切深色图标、背景蒙层用白色）；false = 深色
- version：可选，显示在设置页（"xxx v1.1"），便于识别更新
- colors：11 个颜色全部必填，`#RRGGBB` 或 `#AARRGGBB`

## 热更新

- 导入同 id 的新 zip → 旧主题被覆盖，若正在使用该主题**立即换肤**，无需重启
- 删除当前使用的自定义主题 → 自动回落到「深蓝」内置主题

## 也可以直接导入 theme.json

不带 zip 的单个 theme.json 文件同样支持（导入时自动包装成主题包）。
