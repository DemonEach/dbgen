<#
P1 speed benchmark (see TODO.md).

Runs DEFAULT/MULTI/FILE against a disposable PostgreSQL container for each requested
(rowCount, tableShape) combination, verifies the result (row count, FK integrity), and
writes one CSV row per run plus a per-combination median summary.

Prerequisites:
- A running container named by -ContainerName with the schema from schema.sql applied
  (see the "docker run" / "psql < schema.sql" commands in bench/README.md).
- build/p0check/classes and build/p0check/lib populated by build/p0check/check.ps1
  (this script does not recompile; rerun check.ps1 first if sources changed).

Does not touch git; does not stop/remove the container.
#>
param(
    [int[]] $RowCounts = @(10000, 100000),
    [string[]] $Shapes = @('narrow', 'wide', 'parent_child'),
    [string[]] $Strategies = @('DEFAULT', 'MULTI', 'FILE'),
    [ValidateRange(1, 1000)][int] $Reps = 3,
    [int] $Batch = 1000,
    [ValidateRange(1, 2147483647)][int] $BatchSave = 1000,
    [ValidateRange(0, 100)][int] $Warmups = 0,
    [string] $Tag = 'pilot',
    [string] $ContainerName = 'dbgen-bench',
    [string] $DockerExe = 'docker',
    [int] $Port = 15432,
    [string] $JavaExe = 'C:/Program Files/Java/jdk-21.0.11/bin/java.exe',
    [string] $RepoRoot = 'C:/pet/dbgen'
)
$ErrorActionPreference = 'Stop'

$runDir = "$RepoRoot/bench/run"
New-Item -ItemType Directory -Force $runDir, "$RepoRoot/bench/results" | Out-Null
$classpath = (Get-ChildItem "$RepoRoot/build/p0check/lib" -Filter '*.jar').FullName -join ';'
$classpath = "$RepoRoot/build/p0check/classes;$classpath"

function Invoke-Psql([string]$Sql) {
    $out = $Sql | & $DockerExe exec -i $ContainerName psql -v ON_ERROR_STOP=1 -U postgres -t -A
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $Sql" }
    return ($out | Where-Object { $_ -ne '' })
}

function Reset-Shape([string]$Shape) {
    switch ($Shape) {
        'narrow' { Invoke-Psql 'TRUNCATE narrow RESTART IDENTITY;' | Out-Null; return @('public.narrow') }
        'wide' { Invoke-Psql 'TRUNCATE wide RESTART IDENTITY;' | Out-Null; return @('public.wide') }
        'parent_child' { Invoke-Psql 'TRUNCATE bench_child, bench_parent RESTART IDENTITY;' | Out-Null; return @('public.bench_parent', 'public.bench_child') }
        default { throw "Unknown shape: $Shape" }
    }
}

function Verify-Shape([string]$Shape, [int]$Rows) {
    switch ($Shape) {
        'narrow' {
            $count = [int](Invoke-Psql 'SELECT count(*) FROM narrow;')
            return [pscustomobject]@{ ok = ($count -eq $Rows); detail = "count=$count" }
        }
        'wide' {
            $count = [int](Invoke-Psql 'SELECT count(*) FROM wide;')
            return [pscustomobject]@{ ok = ($count -eq $Rows); detail = "count=$count" }
        }
        'parent_child' {
            $countParent = [int](Invoke-Psql 'SELECT count(*) FROM bench_parent;')
            $countChild = [int](Invoke-Psql 'SELECT count(*) FROM bench_child;')
            $fkViolations = [int](Invoke-Psql 'SELECT count(*) FROM bench_child c LEFT JOIN bench_parent p ON p.id = c.parent_id WHERE p.id IS NULL;')
            $ok = ($countParent -eq $Rows) -and ($countChild -eq $Rows) -and ($fkViolations -eq 0)
            return [pscustomobject]@{ ok = $ok; detail = "parent=$countParent child=$countChild fk_violations=$fkViolations" }
        }
    }
}

function Get-YamlForShape([string]$Shape, [int]$Rows, [string]$Strategy, [string[]]$Tables) {
    $tableLines = ($Tables | ForEach-Object { "  - $_" }) -join "`n"
    return @"
debug: false
amountOfEntries: $Rows
strategy: $Strategy
batchSave: $BatchSave
batch: $Batch
connectionParameters:
  host: localhost
  port: $Port
  username: postgres
  password: postgres
  dbName: postgres
tablesToGenerate:
$tableLines
"@
}

# per-table timestamps include generation, FK reads and insertion, but exclude commit;
# wall_ms includes the entire CLI process through commit and exit.
function Get-GenerationMs([string[]]$LogLines) {
    $starts = @()
    $finishes = @()
    foreach ($line in $LogLines) {
        if ($line -match '^(\d\d:\d\d:\d\d\.\d\d\d).*Starting generation for table') { $starts += [TimeSpan]::Parse($matches[1]) }
        elseif ($line -match '^(\d\d:\d\d:\d\d\.\d\d\d).*Finished generation for table') { $finishes += [TimeSpan]::Parse($matches[1]) }
    }
    if ($starts.Count -eq 0 -or $finishes.Count -eq 0) { return $null }
    return ($finishes[-1] - $starts[0]).TotalMilliseconds
}

$results = @()
$totalCombos = $RowCounts.Count * $Shapes.Count * $Strategies.Count
$comboIndex = 0

foreach ($rows in $RowCounts) {
    foreach ($shape in $Shapes) {
        $tables = Reset-Shape $shape
        foreach ($strategy in $Strategies) {
            $comboIndex++
            Write-Host "[$comboIndex/$totalCombos] rows=$rows shape=$shape strategy=$strategy"
            for ($rep = 1 - $Warmups; $rep -le $Reps; $rep++) {
                Reset-Shape $shape | Out-Null
                $yaml = Get-YamlForShape $shape $rows $strategy $tables
                Set-Content -Path "$runDir/application.yaml" -Value $yaml -Encoding utf8

                Push-Location $runDir
                $sw = [Diagnostics.Stopwatch]::StartNew()
                $log = & $JavaExe -cp $classpath ru.demoneach.dbgenerator.App 2>&1
                $sw.Stop()
                $exitCode = $LASTEXITCODE
                Pop-Location

                $verify = if ($exitCode -eq 0) { Verify-Shape $shape $rows } else { [pscustomobject]@{ ok = $false; detail = 'process failed' } }
                $genMs = Get-GenerationMs ($log | ForEach-Object { $_.ToString() })

                $results += [pscustomobject]@{
                    tag         = $Tag
                    rows        = $rows
                    shape       = $shape
                    strategy    = $strategy
                    batch       = $Batch
                    batch_save  = $BatchSave
                    warmup      = ($rep -le 0)
                    rep         = $rep
                    exit_code   = $exitCode
                    wall_ms     = [math]::Round($sw.Elapsed.TotalMilliseconds, 1).ToString([System.Globalization.CultureInfo]::InvariantCulture)
                    gen_ms      = if ($null -ne $genMs) { [math]::Round($genMs, 1).ToString([System.Globalization.CultureInfo]::InvariantCulture) } else { $null }
                    verify_ok   = $verify.ok
                    verify_note = $verify.detail
                }
                if (-not $verify.ok -or $exitCode -ne 0) {
                    Write-Host "  rep $rep FAILED: exit=$exitCode verify=$($verify.detail)"
                    Write-Host ($log -join "`n")
                }
            }
        }
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csvPath = "$RepoRoot/bench/results/$Tag-$stamp.csv"
$results | Export-Csv $csvPath -NoTypeInformation
Write-Host "`nRaw results: $csvPath"

Write-Host "`nMedian wall_ms / gen_ms per (rows, shape, strategy), n=${Reps}:"
$results | Where-Object { -not $_.warmup -and $_.verify_ok -and $_.exit_code -eq 0 } | Group-Object rows, shape, strategy | ForEach-Object {
    $sorted = $_.Group.wall_ms | ForEach-Object { [double]$_ } | Sort-Object
    $genSorted = $_.Group.gen_ms | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object
    $medianWall = ($sorted[[math]::Floor(($sorted.Count - 1) / 2)] + $sorted[[math]::Floor($sorted.Count / 2)]) / 2
    $medianGen = if ($genSorted.Count -gt 0) { ($genSorted[[math]::Floor(($genSorted.Count - 1) / 2)] + $genSorted[[math]::Floor($genSorted.Count / 2)]) / 2 } else { $null }
    $allOk = ($_.Group.verify_ok -notcontains $false)
    [pscustomobject]@{
        key         = $_.Name
        median_wall = $medianWall
        median_gen  = $medianGen
        all_verified = $allOk
    }
} | Sort-Object key | Format-Table -AutoSize

if ($results | Where-Object { -not $_.verify_ok -or $_.exit_code -ne 0 }) { throw 'Benchmark verification failed; see raw results.' }
