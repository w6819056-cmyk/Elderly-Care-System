[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = 'ChangeMe-Platform-2026'
)

$ErrorActionPreference = 'Stop'
$script:step = 0
$script:token = $null

function Invoke-Platform([string]$Method, [string]$Path, [object]$Body = $null, [int]$Expected = 200, [hashtable]$Headers = @{}) {
    $script:step++
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    try { $response = Invoke-WebRequest @request } catch {
        $detail = $_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue
        if ($detail.code) { throw "[$script:step] $Method $Path failed: $($detail.code) - $($detail.message)" }
        throw "[$script:step] $Method $Path failed: $($_.Exception.Message)"
    }
    if ([int]$response.StatusCode -ne $Expected) { throw "[$script:step] expected HTTP $Expected, got $($response.StatusCode)" }
    $json = $response.Content | ConvertFrom-Json
    if ($json.code -ne 'OK') { throw "[$script:step] expected OK, got $($json.code)" }
    Write-Host "[$script:step] $Method $Path -> $($response.StatusCode) OK" -ForegroundColor Green
    return $json.data
}

try {
    Invoke-Platform GET '/api/platform/v1/health' | Out-Null
    $login = Invoke-Platform POST '/api/platform/v1/auth/login' @{ username = $AdminUsername; password = $AdminPassword }
    $script:token = $login.accessToken
    $auth = @{ Authorization = "Bearer $script:token" }
    $enterprise = Invoke-Platform POST '/api/platform/v1/enterprises' @{ name = "冒烟企业-$([guid]::NewGuid().ToString('N').Substring(0,8))"; contactName = '平台测试'; contactPhone = '13800000000' } 201 $auth
    $code = Invoke-Platform POST "/api/platform/v1/enterprises/$($enterprise.publicId)/activation-codes" @{ ttlHours = 1; maxAttempts = 3 } 201 $auth
    Write-Host '[info] activation code generated and kept hidden' -ForegroundColor DarkGray
    $moduleCode = "smoke-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    $module = Invoke-Platform POST '/api/platform/v1/modules' @{ code = $moduleCode; name = '平台冒烟模块'; description = '平台控制面冒烟模块'; menuOrder = (Get-Random -Minimum 1000 -Maximum 9000); trialEnabled = $false; dependencies = @(); prices = @() } 201 $auth
    Invoke-Platform POST "/api/platform/v1/modules/$moduleCode/versions" @{ version = '1.0.0'; minCoreVersion = '1.0.0' } 201 $auth | Out-Null
    Invoke-Platform PUT "/api/platform/v1/modules/$moduleCode/prices" @{ prices = @(@{ billingMode = 'PERIOD'; durationDays = 365; amount = '1.00'; currency = 'CNY'; storeQuota = 1 }) } 200 $auth | Out-Null
    Invoke-Platform PUT "/api/platform/v1/modules/$moduleCode/status" @{ status = 'ON_SALE' } 200 $auth | Out-Null
    $planCode = "smoke_plan_$([guid]::NewGuid().ToString('N').Substring(0,6))"
    $plan = Invoke-Platform POST '/api/platform/v1/plans' @{ code = $planCode; name = '平台冒烟套餐'; description = '平台控制面冒烟套餐'; amount = '1.00'; minimumAmount = '1.00'; items = @(@{ moduleCode = $module.moduleCode; billingMode = 'PERIOD'; durationDays = 365; storeQuota = 1 }) } 201 $auth
    $quote = Invoke-Platform POST '/api/platform/v1/orders/quote' @{ enterpriseId = $enterprise.publicId; planCode = $plan.planCode; items = @() } 200 $auth
    $order = Invoke-Platform POST '/api/platform/v1/orders' @{ enterpriseId = $enterprise.publicId; planCode = $plan.planCode; items = @() } 201 (@{ Authorization = "Bearer $script:token"; 'Idempotency-Key' = "smoke-$([guid]::NewGuid())" })
    $paid = Invoke-Platform POST "/api/platform/v1/orders/$($order.publicId)/pay-simulated" @{ channel = 'MOCK'; channelTransactionNo = "smoke-$([guid]::NewGuid())" } 200 $auth
    if ($paid.status -ne 'FULFILLED') { throw "[$script:step] unexpected order status $($paid.status)" }
    Write-Host 'Platform smoke flow passed.' -ForegroundColor Green
} catch {
    Write-Error $_
    exit 1
}
