# Builds target\kbmd-<version>.jar (React UI + Spring Boot + tests).
#   .\build.ps1              full build
#   .\build.ps1 -SkipTests   faster
# Needs JDK 25+ and Node 22+. If JAVA_HOME is older, a JDK 25+ from ~\.jdks (IntelliJ downloads) is used.
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'

function Get-JavaMajor($javaHome) {
    $release = Join-Path $javaHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}

if (-not $env:JAVA_HOME -or (Get-JavaMajor $env:JAVA_HOME) -lt 25) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { -not $_.Name.StartsWith('.') -and (Get-JavaMajor $_.FullName) -ge 25 } |
        Sort-Object { Get-JavaMajor $_.FullName }, Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK 25 or newer not found. Install one and set JAVA_HOME.' }
    $env:JAVA_HOME = $jdk.FullName
}
Write-Host "Using JDK: $env:JAVA_HOME"

$mvnArgs = @('-q', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& "$PSScriptRoot\mvnw.cmd" @mvnArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Get-ChildItem "$PSScriptRoot\target\kbmd-*.jar" | ForEach-Object { Write-Host "Built $($_.FullName)" }
