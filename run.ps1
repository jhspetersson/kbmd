# Runs the built jar (run .\build.ps1 first).
#   .\run.ps1                          vault: ~\kbmd-vault
#   .\run.ps1 C:\Users\me\Notes        another markdown folder
#   .\run.ps1 C:\Users\me\Notes -Port 9000
param(
    [Parameter(Position = 0)][string]$Vault,
    [int]$Port = 8787
)
$ErrorActionPreference = 'Stop'

$jar = Get-ChildItem "$PSScriptRoot\target\kbmd-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw 'No jar in target\. Run .\build.ps1 first.' }

# any Java 25+ runtime works: JAVA_HOME if new enough, else the newest JDK in ~\.jdks, else java on PATH
$java = 'java'
$candidates = @($env:JAVA_HOME) + @(Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
    Where-Object { -not $_.Name.StartsWith('.') } | Sort-Object Name -Descending | ForEach-Object FullName)
foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path "$candidate\release") -and
        ((Get-Content "$candidate\release") -match '^JAVA_VERSION="(2[5-9]|[3-9]\d)')) {
        $java = "$candidate\bin\java.exe"
        break
    }
}

$appArgs = @("--server.port=$Port")
if ($Vault) { $appArgs += "--kbmd.vault=$Vault" }
Write-Host "kbmd -> http://localhost:$Port"
& $java -jar $jar.FullName @appArgs
