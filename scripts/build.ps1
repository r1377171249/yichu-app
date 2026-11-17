param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$GradleCache = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw 'Set JAVA_HOME or pass -JavaHome (JDK 21).' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -or -not (Test-Path -LiteralPath "$SdkPath/platforms/android-36/android.jar")) { throw 'Pass -SdkPath or set ANDROID_HOME (API 36).' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkPath
if ($GradleCache) { $env:GRADLE_USER_HOME = $GradleCache }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties'))) {
    Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value ('sdk.dir=' + $SdkPath.Replace('\','/')) -Encoding ascii
}
Push-Location $projectRoot
try {
    & './gradlew.bat' --no-daemon --console=plain assembleDebug testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
} finally { Pop-Location }
