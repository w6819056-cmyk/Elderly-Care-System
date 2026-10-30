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

Write-Host 'All current checks passed.'
