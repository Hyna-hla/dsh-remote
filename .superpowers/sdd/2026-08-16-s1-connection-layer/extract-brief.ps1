param([int]$N)
$plan = Get-Content "E:\AI搓的小东西\harness-remote\docs\superpowers\plans\2026-08-16-s1-connection-layer.md" -Raw -Encoding UTF8
$ws = "E:\AI搓的小东西\harness-remote\.superpowers\sdd\2026-08-16-s1-connection-layer"
$start = [regex]::Match($plan, "(?ms)^### Task $N`:.*?$").Index
if ($start -lt 0) { throw "task $N heading not found" }
$rest = $plan.Substring($start)
$endMatch = [regex]::Match($rest, "(?m)^---\s*$")
$nextTask = [regex]::Match($rest, "(?m)^### Task ")
$end = $rest.Length
if ($nextTask.Success -and $nextTask.Index -gt 0) {
    $before = $rest.Substring(0, $nextTask.Index)
    $m = [regex]::Match($before, "(?m)^---\s*$", [System.Text.RegularExpressions.RegexOptions]::RightToLeft)
    if ($m.Success) { $end = $m.Index } else { $end = $nextTask.Index }
} else {
    $m2 = [regex]::Match($rest, "(?m)^## Self-Review")
    if ($m2.Success) { $end = $m2.Index }
}
$brief = $rest.Substring(0, $end).TrimEnd() + "`n"
$out = Join-Path $ws ("task-{0}-brief.md" -f $N)
[System.IO.File]::WriteAllText($out, $brief, [System.Text.UTF8Encoding]::new($false))
Write-Output $out

