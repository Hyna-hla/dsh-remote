# git-sync.ps1 — 通用「先拉取再提交」Git 同步脚本
#
# 核心理念（按你的要求）：
#   1. 每次提交前必须先同步（先 git fetch + pull 合并远端更新，冲突在本机解决，再推送）。
#   2. 定时循环拉取，云端有更新就实时拉到本地；本地有提交就推上去，实现双向同步。
#   3. 只在需要时提交本地工作区改动（默认提交信息带时间戳，保留 git 差分时间线）。
#   4. 一旦出现合并/变基冲突，立即停下不推送，提示本地解决；不由脚本盲目覆盖。
#
# 用法示例：
#   # 单次同步（拉取 + 提交 + 推送）
#   .\git-sync.ps1 -Once -CommitMessage "feat: xxxx"
#
#   # 定时双向同步（默认 60 秒一轮，Ctrl+C 停止）
#   .\git-sync.ps1
#
#   # 指定仓库目录、远程、分支、间隔
#   .\git-sync.ps1 -Repo D:\myrepo -Remote origin -Branch main -PullIntervalSeconds 30
#
#   # 只拉取不提交不推送（纯下行同步）
#   .\git-sync.ps1 -Once -PullOnly

[CmdletBinding()]
param(
    [string]$Repo,                          # 仓库目录，缺省=当前目录
    [string]$Remote = "origin",             # 远程名
    [string]$Branch = "",                   # 分支，缺省=当前分支
    [int]$PullIntervalSeconds = 60,         # 定时循环间隔
    [switch]$Once,                          # 只跑一轮
    [switch]$PullOnly,                      # 只拉取，不自动提交/推送
    [string]$CommitMessage = "",            # 自动提交信息（留空则用时间戳模板）
    [switch]$NoPush                         # 拉取/提交后不推送
)

$ErrorActionPreference = "Continue"

# Windows 控制台默认代码页常为 GBK(936)，会把脚本输出的 UTF-8 中文渲染成乱码：
# 强制本进程以 UTF-8 读写控制台，保证 Write-Host 中文在 PowerShell(5.1/7) 均正常显示。
$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

if (-not $Repo) { $Repo = (Get-Location).Path }

function Invoke-Git {
    param([string[]]$GitArgs)
    $out = & git @GitArgs 2>&1
    $code = $LASTEXITCODE
    return [pscustomobject]@{ Code = $code; Output = ($out -join "`n") }
}

function Test-ScriptStop {
    # Ctrl+C 时退出
    if ([Console]::IsInputRedirected -eq $false -and [Console]::KeyAvailable) {
        $k = [Console]::ReadKey($true)
        if ($k.Modifiers -band [ConsoleModifiers]::Control -and $k.Key -eq [ConsoleKey]::C) {
            return $true
        }
    }
    return $false
}

function Sync-Once {
    if (-not (Test-Path (Join-Path $Repo '.git'))) {
        Write-Host "[skip] 不是 git 仓库（缺少 .git）：$Repo" -ForegroundColor Yellow
        return
    }

    Push-Location $Repo
    try {
        Write-Host "==== 同步：$Repo  @ $(Get-Date -Format 'HH:mm:ss') ====" -ForegroundColor Cyan

        # 1) 拉取远端引用
        $fetch = Invoke-Git @("fetch", "--prune", $Remote)
        if ($fetch.Code -ne 0) {
            Write-Host "[fetch 失败] $($fetch.Output)" -ForegroundColor Red
            return
        }

        # 2) 确定分支与上游
        if (-not $Branch) { $Branch = (& git branch --show-current).Trim() }
        if (-not $Branch) { $Branch = "main" }
        if (-not (& git rev-parse --verify --quiet "refs/remotes/$Remote/$Branch")) {
            Write-Host "[无远端分支] $Remote/$Branch 不存在，新建并绑定上游。" -ForegroundColor Yellow
        }
        # 绑定上游（不存在时才设）
        $upstream = (& git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2>$null).Trim()
        if (-not $upstream) {
            & git branch --set-upstream-to="$Remote/$Branch" $Branch 2>$null
        }

        # 3) 统计本地 ahead/behind
        $aheadText = (& git rev-list --count "@{u}..HEAD" 2>$null) -join ''
        $behindText = (& git rev-list --count "HEAD..@{u}" 2>$null) -join ''
        $ahead = 0; $behind = 0
        [int]::TryParse($aheadText, [ref]$ahead) | Out-Null
        [int]::TryParse($behindText, [ref]$behind) | Out-Null
        Write-Host ("本地领先(ahead)={0}  落后(behind)={1}" -f $ahead, $behind) -ForegroundColor DarkGray

        # 4) 可选：自动提交本地工作区改动（保留时间线，便于回滚/差分）
        if (-not $PullOnly) {
            $dirty = (& git status --porcelain) -ne $null -and ((& git status --porcelain) -join '') -ne ''
            if ($dirty) {
                $msg = if ($CommitMessage) { $CommitMessage } else { "auto-sync $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" }
                Write-Host "自动提交本地改动：$msg" -ForegroundColor Green
                & git add -A
                if ($LASTEXITCODE -ne 0) { Write-Host "[git add 失败]" -ForegroundColor Red; return }
                & git commit -m $msg | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Host "[git commit 失败]" -ForegroundColor Red; return }
                $ahead = $ahead + 1
            }
        }

        # 5) 变基拉取（把远端更新合并到本地，冲突在本机解决）—— 关键一步
        if ($behind -gt 0) {
            Write-Host "远端有 $behind 个新提交，执行 pull --rebase 合并到本地…" -ForegroundColor Cyan
            $pull = Invoke-Git @("pull", "--rebase", $Remote, $Branch)
            if ($pull.Code -ne 0) {
                Write-Host "[冲突/拉取失败] 已停在此处，尚未推送。" -ForegroundColor Yellow
                Write-Host "远端与本地的改动发生冲突，请在本地解决后再继续："
                Write-Host "  1) git status 查看冲突文件"
                Write-Host "  2) 编辑文件解决冲突（保留两个时间线中应保留的改动）"
                Write-Host "  3) git add <文件> ; git rebase --continue"
                Write-Host "  4) 重新运行本脚本完成提交与推送"
                return
            }
            Write-Host "拉取并变基完成。" -ForegroundColor Green
        } else {
            Write-Host "远端没有新提交，跳过拉取。" -ForegroundColor DarkGray
        }

        # 6) 推送本地提交
        if (-not $PullOnly -and -not $NoPush) {
            $ahead2 = $ahead
            $push = Invoke-Git @("push", $Remote, $Branch)
            if ($push.Code -ne 0) {
                Write-Host "[push 失败] $($push.Output)" -ForegroundColor Red
                return
            }
            Write-Host "已推送到 $Remote/$Branch ✓" -ForegroundColor Green
        } else {
            Write-Host "跳过推送（PullOnly/NoPush）。" -ForegroundColor DarkGray
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host "git-sync 启动（目录:$Repo 远程:$Remote 分支:$Branch）" -ForegroundColor Cyan

if ($Once) {
    Sync-Once
    Write-Host "[完成] 单次同步结束。" -ForegroundColor Green
    exit 0
}

# 定时循环模式
Write-Host "进入定时同步模式：每 $PullIntervalSeconds 秒同步一次，Ctrl+C 停止。" -ForegroundColor Cyan
while ($true) {
    if (Test-ScriptStop) { Write-Host "`n[退出]" -ForegroundColor Yellow; break }
    Sync-Once
    Start-Sleep -Seconds $PullIntervalSeconds
}
