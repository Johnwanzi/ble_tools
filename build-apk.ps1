param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$androidProject = Join-Path $projectRoot 'android'
$localSdk = Join-Path $projectRoot '.android-tools\sdk'
$localGradle = Join-Path $projectRoot '.android-tools\gradle-8.14.3\bin\gradle.bat'

if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath $localSdk)) {
    $env:ANDROID_HOME = $localSdk
}
if (-not $env:ANDROID_HOME -and -not (Test-Path -LiteralPath (Join-Path $androidProject 'local.properties'))) {
    throw 'Install Android SDK 35 and set ANDROID_HOME, or configure android/local.properties.'
}
$gradleCommand = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $androidProject 'gradlew.bat' }
$tasks = @('assembleDebug')
if (-not $SkipChecks) { $tasks += @('lintDebug', 'protocolTest') }
& $gradleCommand -p $androidProject @tasks
if ($LASTEXITCODE -ne 0) { throw "Android build failed (exit $LASTEXITCODE)." }

$outputDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$metadata = Get-Content -Raw -LiteralPath (Join-Path $androidProject 'app\build\outputs\apk\debug\output-metadata.json') | ConvertFrom-Json
$apkName = "ble-tool-$($metadata.elements[0].versionName)-debug.apk"
$apk = Join-Path $outputDir $apkName
Copy-Item -LiteralPath (Join-Path $androidProject 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $null }
if ($sdk) {
    $signer = Join-Path $sdk 'build-tools\35.0.0\apksigner.bat'
    if (Test-Path -LiteralPath $signer) {
        & $signer verify --verbose $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    }
}
$digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
"$digest  $apkName" | Set-Content -Encoding ascii -LiteralPath (Join-Path $outputDir "$apkName.sha256")
Write-Output "APK ready: $apk"
