$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

& (Join-Path $PSScriptRoot 'test-backend-structure.ps1')

mvn -f (Join-Path $root 'backend/pom.xml') clean test
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }

Write-Host 'Backend verification passed.'
