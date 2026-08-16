<#
.SYNOPSIS
    cpolar 隧道看门狗：轮询本地面板(127.0.0.1:4040)，隧道挂掉自动重启，
    可选 Bark 推送通知（手机上点开就是新隧道地址）。

.DESCRIPTION
    与 dsh-remote-start.ps1 -Mode cpolar 配套：那个脚本前台跑隧道（Ctrl+C 即停），
    本脚本后台守护一个独立隧道进程。免费版重连后公网地址会变——配置 Bark 后，
    新地址会推到手机，复制进 App 即可恢复连接。

.PARAMETER Port
    要穿透的本地 DSH web 端口。默认 3080。

.PARAMETER IntervalSec
    健康检查间隔（秒）。默认 30。

.PARAMETER BarkUrl
    可选 Bark 推送地址，如 https://api.day.app/你的key。隧道重建后推送新公网地址。

.EXAMPLE
    .\dsh-tunnel-watchdog.ps1                    # 守护 3080，30s 一查
    .\dsh-tunnel-watchdog.ps1 -BarkUrl https://api.day.app/xxxx
#>
param(
    [int]$Port = 3080,
    [int]$IntervalSec = 30,
    [string]$BarkUrl = ""
)

$ErrorActionPreference = "Continue"

$cpolar = if (Get-Command cpolar -ErrorAction SilentlyContinue) { (Get-Command cpolar).Source }
elseif (Test-Path "$env:USERPROFILE\harness-remote\cpolar\cpolar.exe") { "$env:USERPROFILE\harness-remote\cpolar\cpolar.exe" }
else { Write-Host "[ERROR] 未找到 cpolar.exe" -ForegroundColor Red; exit 1 }

$logTag = "[tunnel-watchdog]"
function Log($msg, $color = "Gray") { Write-Host "$logTag $msg" -ForegroundColor $color }

function Get-TunnelUrl {
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:4040/api/tunnels" -TimeoutSec 5
        $t = $resp.tunnels | Where-Object { $_.proto -eq "https" } | Select-Object -First 1
        if ($t -and $t.public_url) { return [string]$t.public_url }
    } catch {}
    return $null
}

function Start-Tunnel {
    Log "启动隧道（穿透 $Port）..." "Cyan"
    Start-Process -FilePath $cpolar `
        -ArgumentList @("http", "$Port", "-host-header=localhost:$Port", "-region=cn", "-log=stdout") `
        -WindowStyle Hidden
    # 等待面板就绪（最多 20s）
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 2
        $url = Get-TunnelUrl
        if ($url) {
            Log "隧道就绪：$url" "Green"
            if ($BarkUrl -ne "") {
                $title = [uri]::EscapeDataString("DSH 隧道已重建")
                $body = [uri]::EscapeDataString("新地址：$url（复制进 App 重连）")
                try { Invoke-RestMethod -Uri "$BarkUrl/$title/$body" -TimeoutSec 5 | Out-Null } catch {}
            }
            return
        }
    }
    Log "隧道启动超时，下轮重试" "Yellow"
}

Log "看门狗启动：每 ${IntervalSec}s 检查一次，cpolar = $cpolar" "Cyan"
if ($null -eq (Get-TunnelUrl)) { Start-Tunnel }

while ($true) {
    Start-Sleep -Seconds $IntervalSec
    $url = Get-TunnelUrl
    if ($null -eq $url) {
        Log "$(Get-Date -Format 'HH:mm:ss') 隧道无响应，重启" "Yellow"
        Get-Process cpolar -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        Start-Tunnel
    }
}
