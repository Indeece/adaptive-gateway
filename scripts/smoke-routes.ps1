param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
foreach ($route in @('availability', 'quotes')) {
    $response = Invoke-RestMethod -Uri "$BaseUrl/$route/SKU-001" -TimeoutSec 5
    if ($response.sku -ne 'SKU-001' -or $response.available -ne $true) { throw "Invalid $route response" }
    if ($route -eq 'availability' -and $response.quantity -ne 10) { throw 'Invalid quantity' }
    if ($route -eq 'quotes' -and ($response.price -ne 100 -or $response.currency -ne 'RUB')) { throw 'Invalid quote' }
    $errorFile = [System.IO.Path]::GetTempFileName()
    try {
        $status = & curl.exe --silent --show-error --max-time 5 --output $errorFile --write-out '%{http_code}' "$BaseUrl/$route/UNKNOWN"
        if ($LASTEXITCODE -ne 0 -or $status -ne '404') { throw "Expected 404 for $route, got $status" }
        if ((Get-Content -Raw -LiteralPath $errorFile | ConvertFrom-Json).code -ne 'not_found') { throw 'Invalid error code' }
    } finally { Remove-Item -LiteralPath $errorFile }
    Write-Output "${route}: valid 200 and unknown SKU 404 verified"
}
