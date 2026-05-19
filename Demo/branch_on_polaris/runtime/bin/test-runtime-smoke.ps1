$ErrorActionPreference = "Stop"

$base = "http://localhost:8181"
$realm = "POLARIS"
$catalog = "quickstart_catalog"

# Smoke test only validates that Polaris is reachable and that the bootstrap
# catalog exists. It does not create Iceberg tables or touch branch state.
$health = Invoke-RestMethod -Uri "http://localhost:8182/q/health" -Method Get
Write-Host "Health:" ($health.status | ConvertTo-Json -Compress)

$tokenResponse = Invoke-RestMethod `
  -Uri "$base/api/catalog/v1/oauth/tokens" `
  -Method Post `
  -ContentType "application/x-www-form-urlencoded" `
  -Body "grant_type=client_credentials&client_id=root&client_secret=s3cr3t&scope=PRINCIPAL_ROLE:ALL"
$token = $tokenResponse.access_token
if (-not $token) {
  throw "Unable to obtain Polaris access token"
}
Write-Host "Token: ok"

$headers = @{
  Authorization = "Bearer $token"
  "Polaris-Realm" = $realm
}

$catalogs = Invoke-RestMethod `
  -Uri "$base/api/management/v1/catalogs" `
  -Method Get `
  -Headers $headers
Write-Host "Catalogs:" ($catalogs | ConvertTo-Json -Compress)

$namespaces = Invoke-RestMethod `
  -Uri "$base/api/catalog/v1/$catalog/namespaces" `
  -Method Get `
  -Headers @{ Authorization = "Bearer $token" }
Write-Host "Namespaces:" ($namespaces | ConvertTo-Json -Compress)

Write-Host "Polaris runtime smoke test passed."
