param(
  [Parameter(Mandatory = $true)]
  [string]$SqlFile
)

$ErrorActionPreference = "Stop"

$runtimeRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $runtimeRoot "docker-compose.yml"
$sqlPath = Resolve-Path $SqlFile
$sqlDir = Split-Path -Parent $sqlPath
$sqlName = Split-Path -Leaf $sqlPath

. (Join-Path $PSScriptRoot "spark-catalog-config.ps1")
$sparkArgs = Get-P0SparkCatalogArgs

# Launch an ephemeral Spark SQL client in the compose network so it can reach
# Polaris as `http://polaris:8181` and RustFS as `http://rustfs:9000`.
docker compose -f $composeFile --profile spark run --rm `
  -v "${sqlDir}:/work/sql:ro" `
  spark-sql `
  /opt/spark/bin/spark-sql `
  @sparkArgs `
  -f "/work/sql/$sqlName"

if ($LASTEXITCODE -ne 0) {
  throw "spark-sql failed with exit code $LASTEXITCODE"
}
