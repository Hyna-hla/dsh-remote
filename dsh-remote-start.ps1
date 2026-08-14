<#
.SYNOPSIS
    启动 DeepSeek Harness 服务，供手机端 DSH Remote 连接（局域网直连 或 cpolar 内网穿透）。

.PARAMETER Port
    独立 DSH 服务端口（仅当没找到桌面版实例时使用）。默认 8787。

.PARAMETER Profile
    DSH profile。默认 mobile（干净 profile）；可用 web 加载完整插件集。

.PARAMETER Mode
    lan  = 局域网直连（手机与 PC 同一 Wi-Fi，App 填局域网 IP）
    cpolar = 内网穿透（任何网络可连；需已安装并登录 cpolar，App 填 cpolar 域名）

.PARAMETER TargetPort
    cpolar 模式要穿透的目标端口：
      auto（默认）= 自动探测正在运行的桌面版 DeepSeek Harness 端口（推荐）
      <数字>     = 指定端口，如 64966

.EXAMPLE
    .\dsh-remote-start.ps1                    # 局域网模式
    .\dsh-remote-start.ps1 -Mode cpolar       # 穿透桌面版 DSH（自动找端口）
    .\dsh-remote-start.ps1 -Mode cpolar -TargetPort 8787
#>

param(
    [int]$Port = 8787,
    [string]$Profile = "mobile",
    [ValidateSet("lan", "cpolar")]
    [string]$Mode = "lan",
    [string]$TargetPort = "auto"
)

$ErrorActionPreference = "Stop"

$DshNode = "E:\DeepSeek Harness-Setup-0.1.0\resources\node\node.exe"
$DshBin  = "E:\DeepSeek Harness-Setup-0.1.0\resources\dsh\node_modules\@deepseek-ai\dsh\lib\bin.js"
$DshHome = "C:\Users\Administrator\.dsh"

Write-Host "===== DSH Remote for Mobile ($Mode) =====" -ForegroundColor Cyan

$profileDir = Join-Path $DshHome "profiles\$Profile"
if (-not (Test-Path (Join-Path $profileDir "package.json"))) {
    Write-Host "[ERROR] Profile '$Profile' 不存在于 $profileDir" -ForegroundColor Red
    Get-ChildItem (Join-Path $DshHome "profiles") -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
    exit 1
}

# 局域网 IP：取有默认网关的活动网卡（避免 WSL/Hyper-V 虚拟网卡 172.x）
$localIp = (Get-NetIPConfiguration | Where-Object {
    $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up"
} | Select-Object -First 1).IPv4Address.IPAddress
if (-not $localIp) { $localIp = "<your-pc-ip>" }

# 防火墙
$ruleName = "DSH Remote $Port"
if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow | Out-Null
    Write-Host "[FIREWALL] 已放行端口 $Port" -ForegroundColor Green
}

# —— 自动探测桌面版 DSH 的监听端口 ——
function Find-DesktopDshPort {
    $procs = Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object {
        $_.CommandLine -match "dsh[\\/]lib[\\/]bin\.js" -and $_.CommandLine -match "\bweb\b"
    }
    foreach ($p in $procs) {
        $lp = Get-NetTCPConnection -State Listen -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalAddress -in @("127.0.0.1", "0.0.0.0", "::") } | Select-Object -First 1
        if ($lp) { return [int]$lp.LocalPort }
    }
    return 0
}

# —— cpolar 模式预检：安装 + 登录 ——
$cpolarExe = $null
if ($Mode -eq "cpolar") {
    $cmd = Get-Command cpolar -ErrorAction SilentlyContinue
    $cpolarExe = if ($cmd) { $cmd.Source } elseif (Test-Path "E:\coplar\cpolar.exe") { "E:\coplar\cpolar.exe" } else { $null }
    if (-not $cpolarExe) {
        Write-Host "  [ERROR] 未检测到 cpolar。" -ForegroundColor Red
        Write-Host "  1. 前往 https://www.cpolar.com 下载 Windows 客户端并安装" -ForegroundColor White
        Write-Host "  2. 注册登录后在 https://dashboard.cpolar.com/auth 复制 authtoken" -ForegroundColor White
        Write-Host "  3. 执行: cpolar authtoken <你的token>" -ForegroundColor White
        exit 1
    }
    $cfgFile = "$env:USERPROFILE\.cpolar\cpolar.yml"
    $authed = (Test-Path $cfgFile) -and ((Get-Content $cfgFile -Raw) -match "authtoken")
    if (-not $authed) {
        Write-Host "  [ERROR] cpolar 未登录（not found authtoken）。" -ForegroundColor Red
        Write-Host "  1. 打开 https://dashboard.cpolar.com/auth 复制你的 authtoken" -ForegroundColor White
        Write-Host "  2. 执行: & '$cpolarExe' authtoken <你的token>" -ForegroundColor White
        Write-Host "  3. 重新运行本脚本" -ForegroundColor White
        exit 1
    }
    Write-Host "[INFO] cpolar 已就绪：$cpolarExe" -ForegroundColor Green
}

$tunnelPort = 0
if ($Mode -eq "cpolar") {
    if ($TargetPort -ne "auto") {
        $tunnelPort = [int]$TargetPort
        Write-Host "[INFO] 使用指定目标端口 $tunnelPort" -ForegroundColor Cyan
    } else {
        $tunnelPort = Find-DesktopDshPort
        if ($tunnelPort -gt 0) {
            Write-Host "[INFO] 检测到桌面版 DSH，端口 $tunnelPort" -ForegroundColor Green
        } else {
            Write-Host "[INFO] 未检测到桌面版 DSH，将自启独立服务（端口 $Port）" -ForegroundColor Yellow
        }
    }
}

# 启动 DSH web 服务（后台）——仅 LAN 模式，或 cpolar 模式没找到桌面实例时
if ($Mode -eq "lan" -or $tunnelPort -eq 0) {
    $webArgs = @($DshBin, "--profile", $Profile, "--host", "0.0.0.0", "--port", $Port)
    if ($localIp -ne "<your-pc-ip>") {
        $webArgs += @("--trusted-host", $localIp)
    }
    Write-Host "[INFO] 启动 DSH web 服务（端口 $Port，信任 $localIp）..." -ForegroundColor Cyan
    Start-Process -FilePath $DshNode -ArgumentList $webArgs -WindowStyle Minimized
    Start-Sleep -Seconds 4
}

if ($Mode -eq "lan") {
    Write-Host ""
    Write-Host "  ✔ DSH 服务已启动" -ForegroundColor Green
    Write-Host "  局域网地址（App 里填这个）:" -ForegroundColor White
    Write-Host "    http://${localIp}:$Port" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  手机和电脑需在同一 Wi-Fi。停止服务：任务管理器结束 node.exe。" -ForegroundColor DarkGray
} else {
    if ($tunnelPort -eq 0) { $tunnelPort = $Port }
    Write-Host ""
    Write-Host "  ✔ 目标就绪（穿透端口 $tunnelPort）" -ForegroundColor Green
    Write-Host "  正在建立 cpolar 隧道（国内节点），公网地址见下方输出..." -ForegroundColor Cyan
    Write-Host "  把形如 https://xxxx.cpolar.top 的地址填进 App 即可（任何网络可用）" -ForegroundColor Yellow
    Write-Host "  说明：-host-header 把 Host 重写为 localhost（DSH /api 信任围栏要求）" -ForegroundColor DarkGray
    Write-Host "  Ctrl+C 停止隧道" -ForegroundColor DarkGray
    Write-Host ""
    & $cpolarExe http $tunnelPort -host-header="localhost:$tunnelPort" -region=cn
}
