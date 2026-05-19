param(
  [switch]$SkipComposeUp
)

$ErrorActionPreference = "Stop"

$runtimeRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$demoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $runtimeRoot "docker-compose.yml"
$controller = Join-Path $demoRoot "controller\branch_controller.py"
$sparkSql = Join-Path $runtimeRoot "bin\invoke-spark-sql.ps1"
$collectState = Join-Path $runtimeRoot "bin\collect-table-state.ps1"
$tables = "sales.orders,sales.customers"
$generatedDir = Join-Path $runtimeRoot "generated"
$stateDir = Join-Path $generatedDir "state"
$sqlDir = Join-Path $generatedDir "sql"
$manifest = Join-Path $generatedDir "branches.json"

New-Item -ItemType Directory -Force -Path $stateDir, $sqlDir | Out-Null

function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath,
    [string[]]$Arguments = @(),
    [int[]]$AllowedExitCodes = @(0)
  )

  & $FilePath @Arguments
  $exitCode = $LASTEXITCODE
  if ($AllowedExitCodes -notcontains $exitCode) {
    throw "Command failed with exit code ${exitCode}: $FilePath $($Arguments -join ' ')"
  }
}

if (-not $SkipComposeUp) {
  Invoke-Native "docker" @("compose", "-f", $composeFile, "up", "-d")
}

# The workflow intentionally routes all branch creation/publish SQL through the
# controller. SQL files under runtime/spark only create and mutate demo tables.
Write-Host "1. Create base Iceberg tables on main"
& $sparkSql `
  -SqlFile (Join-Path $runtimeRoot "spark\01-init-main-tables.sql")

$baseState = Join-Path $stateDir "main-base.json"
Write-Host "2. Collect main base state"
& $collectState `
  -Tables $tables `
  -Ref "main" `
  -Output $baseState

Write-Host "3. Initialize branch manifest and generate branch SQL"
Invoke-Native "python" @($controller, "--manifest", $manifest, "init", "--catalog", "polaris", "--force")

$createDevA = Join-Path $sqlDir "create-dev-a.sql"
$createDevB = Join-Path $sqlDir "create-dev-b.sql"
Invoke-Native "python" @(
  $controller, "--manifest", $manifest, "create", "dev_a",
  "--tables", $tables,
  "--state-file", $baseState,
  "--sql-out", $createDevA
)
Invoke-Native "python" @(
  $controller, "--manifest", $manifest, "create", "dev_b",
  "--tables", $tables,
  "--state-file", $baseState,
  "--sql-out", $createDevB
)

Write-Host "4. Execute generated CREATE BRANCH SQL"
& $sparkSql -SqlFile $createDevA
& $sparkSql -SqlFile $createDevB

Write-Host "5. Advance dev_a and dev_b independently"
& $sparkSql `
  -SqlFile (Join-Path $runtimeRoot "spark\03-advance-branches.sql")

$devAState = Join-Path $stateDir "dev-a-head.json"
$devBState = Join-Path $stateDir "dev-b-head.json"
$mainAfterBranchWrites = Join-Path $stateDir "main-after-branch-writes.json"

Write-Host "6. Collect branch head state and current main state"
& $collectState -Tables $tables -Ref "dev_a" -Output $devAState
& $collectState -Tables $tables -Ref "dev_b" -Output $devBState
& $collectState -Tables $tables -Ref "main" -Output $mainAfterBranchWrites

Write-Host "7. Diff logical branches against recorded base"
Invoke-Native "python" @($controller, "--manifest", $manifest, "diff", "dev_a", "--state-file", $devAState)
Invoke-Native "python" @($controller, "--manifest", $manifest, "diff", "dev_b", "--state-file", $devBState)

$publishDevA = Join-Path $sqlDir "publish-dev-a.sql"
Write-Host "8. Publish dev_a with fast-forward preflight"
Invoke-Native "python" @(
  $controller, "--manifest", $manifest, "publish-ff", "dev_a",
  "--state-file", $mainAfterBranchWrites,
  "--sql-out", $publishDevA
)
& $sparkSql -SqlFile $publishDevA

$mainAfterDevA = Join-Path $stateDir "main-after-dev-a-publish.json"
Write-Host "9. Collect main after dev_a publish and verify dev_b conflict"
& $collectState -Tables $tables -Ref "main" -Output $mainAfterDevA
Invoke-Native "python" @(
  $controller, "--manifest", $manifest, "publish-ff", "dev_b",
  "--state-file", $mainAfterDevA
) -AllowedExitCodes @(2)

Write-Host "P0 overlay demo completed. Generated files: $generatedDir"
