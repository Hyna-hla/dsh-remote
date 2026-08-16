<#
.SYNOPSIS
    Cloudflare Tunnel 启动脚本：免登录 quick tunnel 穿透本地 DSH web，
    固定不变带宽限制、不抽风断流（对比 cpolar 免费版）。

.DESCRIPTION
    - 自动下载 cloudflared.exe（GitHub Release，走 gh-proxy 镜像兜底）
    - quick tunnel 无需账号：每次启动分配随机 trycloudflare.com 地址
      （重启会变；要固定域名需 `cloudflared tunnel login` 后建 named tunnel）
    - --http-host-header 把 Host 重写为 localhost 绕过 DSH /api 信任围栏
      （与 cpolar 模式的 -host-header 同理）
    - cloudflared 会给请求带 X-Forwarded-For → 触发插件 token 鉴权：
      手机 App 需 v1.3.4+ 并完成配对（自动拿 token），本机浏览器不受影响

.PARAMETER Port
    本地 DSH web 端口。默认 3080。

.EXAMPLE
    .\dsh-cloudflared.ps1                # 穿透 3080，输出 https://xxxx.trycloudflare.com
    .\dsh-cloudflared.ps1 -Port 8787
#>
param(
    [int]$Port = 3080
)

$ErrorActionPreference = "Stop"

$dir = "$env:USERPROFILE\harness-remote\cloudflared"
$exe = Join-Path $dir "cloudflared.exe"

if (-not (Test-Path $exe)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Write-Host "[INFO] 下载 cloudflared..." -ForegroundColor Cyan
    $ver = "2025.11.1"
    $urls = @(
        "https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/download/v$ver/cloudflared-windows-amd64.exe",
        "https://github.com/cloudflare/cloudflared/releases/download/v$ver/cloudflared-windows-amd64.exe"
    )
    $ok = $false
    foreach ($u in $urls) {
        try {
            Invoke-WebRequest -Uri $u -OutFile $exe -TimeoutSec 300 -UseBasicParsing
            $ok = $true
            break
        } catch { Write-Host "[WARN] 源失败：$u" -ForegroundColor Yellow }
    }
    if (-not $ok) { Write-Host "[ERROR] cloudflared 下载失败，手动下载放至 $exe" -ForegroundColor Red; exit 1 }
}
else { Write-Host "[INFO] cloudflared 已就绪：$exe" -ForegroundColor Green }

Write-Host ""
Write-Host "  正在建立 Cloudflare quick tunnel（穿透 $Port）..." -ForegroundColor Cyan
Write-Host "  把输出里形如 https://xxxx.trycloudflare.com 的地址填进 App" -ForegroundColor Yellow
Write-Host "  （免费 quick tunnel 每次重启换地址；固定域名需 cloudflared tunnel login 后建 named tunnel）" -ForegroundColor DarkGray
Write-Host "  Ctrl+C 停止隧道" -ForegroundColor DarkGray
Write-Host ""
& $exe tunnel --url "http://localhost:$Port" --http-host-header "localhost:$Port"
