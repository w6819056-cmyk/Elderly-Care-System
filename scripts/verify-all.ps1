$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

& (Join-Path $PSScriptRoot 'verify-backend.ps1')

Push-Location (Join-Path $root 'admin')
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Admin build failed' }
} finally {
    Pop-Location
}

foreach ($app in @('admin-platform', 'admin-tenant')) {
    Push-Location (Join-Path $root $app)
    try {
        npm test -- --run
        if ($LASTEXITCODE -ne 0) { throw "$app tests failed" }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "$app build failed" }
    } finally {
        Pop-Location
    }
}

Write-Host 'All current checks passed.'
