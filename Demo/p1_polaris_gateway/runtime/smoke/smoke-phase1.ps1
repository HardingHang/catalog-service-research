param(
  [string] $GatewayUrl = "http://localhost:8080",
  [int] $MinOpenApiPaths = 15
)

$ErrorActionPreference = "Stop"

$health = Invoke-RestMethod -Uri "$GatewayUrl/q/health" -Headers @{ Accept = "application/json" }
if ($health.status -ne "UP") {
  throw "Gateway health is $($health.status); expected UP"
}

$openapi = Invoke-RestMethod -Uri "$GatewayUrl/q/openapi" -Headers @{ Accept = "application/json" }
$pathCount = ($openapi.paths.PSObject.Properties | Measure-Object).Count
if ($pathCount -lt $MinOpenApiPaths) {
  throw "OpenAPI path count is $pathCount; expected at least $MinOpenApiPaths"
}

Write-Host "Phase 1 smoke passed: health=$($health.status), openapiPaths=$pathCount"
