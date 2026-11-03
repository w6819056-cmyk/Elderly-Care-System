$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$required = @(
    'backend/pom.xml',
    'backend/apps/platform-app/pom.xml',
    'backend/apps/tenant-app/pom.xml',
    'backend/libs/care-common/pom.xml',
    'backend/libs/care-security/pom.xml',
    'backend/libs/care-core-api/pom.xml',
    'backend/libs/care-business-api/pom.xml',
    'backend/libs/care-module-api/pom.xml',
    'backend/core/care-core/pom.xml',
    'backend/core/care-business/pom.xml',
    'backend/modules/institution/institution-api/pom.xml',
    'backend/modules/institution/institution-impl/pom.xml',
    'backend/modules/community/community-api/pom.xml',
    'backend/modules/community/community-impl/pom.xml',
    'backend/modules/membership/membership-api/pom.xml',
    'backend/modules/membership/membership-impl/pom.xml',
    'backend/platform/platform-core/pom.xml',
    'backend/platform/platform-licensing/pom.xml',
    'backend/tests/architecture-tests/pom.xml'
)
$missing = $required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) }
if ($missing.Count -gt 0) {
    throw ('Missing backend module POM(s): ' + ($missing -join ', '))
}
Write-Host 'Backend structure is valid.'
