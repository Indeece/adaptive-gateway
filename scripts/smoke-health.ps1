param(
    [ValidateRange(1, 65535)][int]$GatewayPort = 8080,
    [ValidateRange(1, 65535)][int]$CheckoutPort = 8081,
    [ValidateRange(1, 65535)][int]$InventoryPort = 8082,
    [ValidateRange(1, 300)][int]$StartupTimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$javaPath = (Get-Command java -ErrorAction Stop).Source
$services = @(
    @{ Name = 'gateway'; Port = $GatewayPort },
    @{ Name = 'checkout'; Port = $CheckoutPort },
    @{ Name = 'inventory'; Port = $InventoryPort }
)
if (@($services.Port | Select-Object -Unique).Count -ne 3) {
    throw 'Each service requires a different port.'
}
foreach ($service in $services) {
    $service.Jar = Join-Path $projectRoot "$($service.Name)/target/$($service.Name)-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $service.Jar)) { throw "Build first: missing $($service.Jar)" }
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $service.Port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$logDirectory = Join-Path $projectRoot ('target/health-smoke/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function Assert-Up($service, $path) {
    if ($service.Process.HasExited) { throw "$($service.Name) exited; inspect $logDirectory" }
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$($service.Port)$path" -TimeoutSec 3
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    if ($response.StatusCode -ne 200 -or ($content | ConvertFrom-Json).status -ne 'UP') {
        throw "$($service.Name) $path is not UP"
    }
}
try {
    foreach ($service in $services) {
        $portVariable = $service.Name.ToUpperInvariant() + '_PORT'
        $previousPort = [Environment]::GetEnvironmentVariable($portVariable, 'Process')
        try {
            [Environment]::SetEnvironmentVariable($portVariable, [string]$service.Port, 'Process')
            $service.Process = Start-Process -FilePath $javaPath -ArgumentList @('-jar', ('"' + $service.Jar + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory "$($service.Name).out.log") -RedirectStandardError (Join-Path $logDirectory "$($service.Name).err.log")
        } finally {
            [Environment]::SetEnvironmentVariable($portVariable, $previousPort, 'Process')
        }
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $ready = $false
        while ($timer.Elapsed.TotalSeconds -lt $StartupTimeoutSeconds) {
            if ($service.Process.HasExited) { throw "$($service.Name) exited; inspect $logDirectory" }
            try {
                Assert-Up $service '/actuator/health/readiness'
                $ready = $true
                break
            } catch {
                Start-Sleep -Milliseconds 250
            }
        }
        if (-not $ready) { throw "$($service.Name) did not become ready; inspect $logDirectory" }
        foreach ($path in @('/actuator/health', '/actuator/health/liveness', '/actuator/health/readiness')) {
            Assert-Up $service $path
        }
        # Only health is published over HTTP.
        $status = 0
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$($service.Port)/actuator/env" -TimeoutSec 3
            $status = [int]$response.StatusCode
        } catch {
            if ($null -eq $_.Exception.Response) { throw }
            $status = [int]$_.Exception.Response.StatusCode
        }
        if ($status -ne 404) { throw "$($service.Name) unexpectedly exposes /actuator/env (HTTP $status)" }
        Write-Output "$($service.Name): health/liveness/readiness UP on $($service.Port); env HTTP 404"
    }
    $services[2].Process.Kill()
    if (-not $services[2].Process.WaitForExit(10000)) { throw 'Inventory did not stop.' }
    foreach ($service in $services[0..1]) {
        Assert-Up $service '/actuator/health/readiness'
    }
    Write-Output 'Gateway and Checkout remain ready after Inventory stops.'
    Write-Output "Health smoke passed. Logs: $logDirectory"
} finally {
    foreach ($service in $services) {
        if ($service.ContainsKey('Process') -and -not $service.Process.HasExited) {
            $service.Process.Kill()
            if (-not $service.Process.WaitForExit(10000)) { Write-Warning "$($service.Name) did not stop." }
        }
    }
}
