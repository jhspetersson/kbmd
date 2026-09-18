# Builds the Android app: the React UI into the APK's assets, then the APK itself.
#   .\build.ps1            release APK (signed when keystore\signing.properties exists) -> kbmd.apk
#   .\build.ps1 -Debug     debug APK
#   .\build.ps1 -SkipUi    reuse the UI built last time
# Needs Node 22+, a JDK 17-21 and the Android SDK (sdk.dir in local.properties, or ANDROID_HOME).
param([switch]$Debug, [switch]$SkipUi)
$ErrorActionPreference = 'Stop'

function Get-JavaMajor($javaHome) {
    $release = Join-Path $javaHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}

# the Android Gradle plugin is happiest on an LTS JDK
$major = if ($env:JAVA_HOME) { Get-JavaMajor $env:JAVA_HOME } else { 0 }
if ($major -lt 17 -or $major -gt 21) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { -not $_.Name.StartsWith('.') -and (Get-JavaMajor $_.FullName) -ge 17 -and (Get-JavaMajor $_.FullName) -le 21 } |
        Sort-Object { Get-JavaMajor $_.FullName }, Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK 17 to 21 not found. Install one and set JAVA_HOME.' }
    $env:JAVA_HOME = $jdk.FullName
}
Write-Host "Using JDK: $env:JAVA_HOME"

if (-not $SkipUi) {
    Push-Location "$PSScriptRoot\..\frontend"
    try {
        if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
        npm run build:android
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}

$variant = if ($Debug) { 'Debug' } else { 'Release' }
& "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot -q "assemble$variant"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Get-ChildItem "$PSScriptRoot\app\build\outputs\apk\$($variant.ToLower())\*.apk" | Select-Object -First 1
Copy-Item $apk.FullName "$PSScriptRoot\kbmd.apk" -Force
Write-Host "Built $PSScriptRoot\kbmd.apk ($([math]::Round($apk.Length / 1MB, 1)) MB, $($apk.Name))"
