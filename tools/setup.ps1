param([switch]$AcceptSdkLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $toolsRoot | Out-Null
function Download-Archive($url, $name, $destination) {
    if (Test-Path -LiteralPath $destination) { return }
    $file = Join-Path $toolsRoot $name
    Invoke-WebRequest -Uri $url -OutFile $file
    Expand-Archive -LiteralPath $file -DestinationPath $destination
}
Download-Archive 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'jdk.zip' (Join-Path $toolsRoot 'jdk')
Download-Archive 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' 'gradle.zip' (Join-Path $toolsRoot 'gradle')
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$cliRoot = Join-Path $sdkRoot 'cmdline-tools\latest'
Download-Archive 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'android-cli.zip' $cliRoot
$nestedRoot = Join-Path $cliRoot 'cmdline-tools'
if (Test-Path -LiteralPath $nestedRoot) {
    $resolvedNested = (Resolve-Path -LiteralPath $nestedRoot).Path
    if (-not $resolvedNested.StartsWith($cliRoot + '\')) { throw 'Invalid tool path' }
    Get-ChildItem -LiteralPath $resolvedNested | Move-Item -Destination $cliRoot
}
$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $toolsRoot 'jdk') -Directory | Select-Object -First 1).FullName
$manager = Join-Path $cliRoot 'bin\sdkmanager.bat'
if ($AcceptSdkLicenses) { 1..30 | ForEach-Object { 'y' } | & $manager "--sdk_root=$sdkRoot" --licenses }
& $manager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подготовить Android SDK' }
"sdk.dir=$($sdkRoot.Replace('\','/'))" | Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding ASCII
$gradleConfigDir = Join-Path $projectRoot '.gradle'
New-Item -ItemType Directory -Force $gradleConfigDir | Out-Null
$gradleConfigFile = Join-Path $gradleConfigDir 'config.properties'
$existingConfig = if (Test-Path -LiteralPath $gradleConfigFile) { Get-Content -LiteralPath $gradleConfigFile | Where-Object { $_ -notmatch '^java\.home=' } } else { @() }
@($existingConfig; "java.home=$($env:JAVA_HOME.Replace('\','/'))") | Set-Content -LiteralPath $gradleConfigFile -Encoding ASCII
Write-Output 'Среда готова. Выполните .\build.ps1 из корня проекта.'
