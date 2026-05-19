param(
  [string]$Tables = "sales.orders,sales.customers",
  [string]$Ref = "main",
  [Parameter(Mandatory = $true)]
  [string]$Output
)

$ErrorActionPreference = "Stop"

$runtimeRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $runtimeRoot "docker-compose.yml"
$toolsDir = Join-Path $runtimeRoot "tools"
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$outputDir = Split-Path -Parent $outputPath
$outputName = Split-Path -Leaf $outputPath

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

. (Join-Path $PSScriptRoot "spark-catalog-config.ps1")
$sparkArgs = Get-P0SparkCatalogArgs

# Run the Python collector inside the Spark image so it has the Iceberg Spark
# extensions and the same REST catalog configuration as the SQL runner.
docker compose -f $composeFile --profile spark run --rm `
  -v "${toolsDir}:/work/tools:ro" `
  -v "${outputDir}:/work/out" `
  spark-sql `
  /opt/spark/bin/spark-submit `
  @sparkArgs `
  /work/tools/collect_iceberg_ref_state.py `
  --tables $Tables `
  --ref $Ref `
  --output "/work/out/$outputName"

if ($LASTEXITCODE -ne 0) {
  throw "collect-table-state failed with exit code $LASTEXITCODE"
}
