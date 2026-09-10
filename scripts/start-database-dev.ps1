$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaExe = 'D:\software\dev\jdk-17\bin\java.exe'
$pythonExe = 'D:\software\dev\anaconda3\python.exe'
$ffmpegExe = 'D:\software\tools\oopz\ffmpeg.exe'
$ffprobeExe = 'D:\software\dev\Trae\Trae CN\resources\app\bin\ffprobe.exe'
foreach ($requiredPath in @($javaExe, $pythonExe, $ffmpegExe, $ffprobeExe)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Missing runtime: $requiredPath" }
}
$runningServices = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'video-(api|worker)[\\/]target[\\/]video-(api|worker)-.*\.jar' }
if ($runningServices) { throw 'Project API/Worker is already running; stop the verified old instance before starting.' }
foreach ($port in @(8080, 3000)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port already occupied: $port" }
}
$runDir = Join-Path $projectRoot ('logs\database-deploy-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDir | Out-Null
$apiJar = Join-Path $projectRoot 'video-api\target\video-api-1.0.0-SNAPSHOT.jar'
$workerJar = Join-Path $projectRoot 'video-worker\target\video-worker-1.0.0-SNAPSHOT.jar'
foreach ($jar in @($apiJar, $workerJar)) { if (-not (Test-Path -LiteralPath $jar)) { throw "Build missing: $jar" } }
$launched = @()
try {
    $api = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $apiJar + '"'), '--spring.profiles.active=dev', '--logging.level.com.videoai.infra.mysql.mapper=INFO') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'api.log') -RedirectStandardError (Join-Path $runDir 'api.err.log')
    $launched += $api
    $worker = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $workerJar + '"'), '--spring.profiles.active=dev', ('"--analysis.media.ffmpeg=' + $ffmpegExe + '"'), ('"--analysis.media.ffprobe=' + $ffprobeExe + '"'), '--logging.level.com.videoai.infra.mysql.mapper=INFO') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'worker.log') -RedirectStandardError (Join-Path $runDir 'worker.err.log')
    $launched += $worker
    $frontend = Start-Process -FilePath $pythonExe -ArgumentList @('-m', 'http.server', '3000', '--bind', '127.0.0.1', '--directory', ('"' + (Join-Path $projectRoot 'frontend') + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'frontend.log') -RedirectStandardError (Join-Path $runDir 'frontend.err.log')
    $launched += $frontend
    $record = @{ startedAt = (Get-Date).ToString('o'); api = $api.Id; worker = $worker.Id; frontend = $frontend.Id; logs = $runDir; profile = 'dev'; dispatch = 'database' }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'processes.json') -Encoding UTF8
    $runDir | Set-Content -LiteralPath (Join-Path $projectRoot 'logs\database-deploy-latest.txt') -Encoding UTF8
    $record | ConvertTo-Json -Compress
} catch {
    # Only stop processes created by this invocation when process launch itself fails.
    foreach ($serviceProcess in $launched) { if (-not $serviceProcess.HasExited) { $serviceProcess.Kill() } }
    throw
}
