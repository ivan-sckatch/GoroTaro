param([switch]$DeviceTests, [switch]$Release)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Укажите JAVA_HOME на JDK 17 или выполните tools\setup.ps1' }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$tasks = @(':app:assembleDebug', ':domain:test', ':app:lintDebug')
if ($DeviceTests) { $tasks += ':app:connectedDebugAndroidTest' }
if ($Release) { $tasks += ':app:assembleRelease' }
$localGradle = Join-Path $projectRoot '.tools\gradle\gradle-8.11.1\bin\gradle.bat'
Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $localGradle) { & $localGradle @tasks --no-daemon --console=plain }
    else { & '.\gradlew.bat' @tasks --no-daemon --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Сборка завершилась с кодом $LASTEXITCODE" }
} finally { Pop-Location }
