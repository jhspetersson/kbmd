# Runs kbmd in Docker on a local markdown folder:
#   .\kbmd.ps1 C:\Users\me\Notes
#   .\kbmd.ps1 C:\Users\me\Notes -Port 9000 -Rebuild
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Vault,
    [int]$Port = 8787,
    [switch]$Rebuild
)
$ErrorActionPreference = 'Stop'

New-Item -ItemType Directory -Force $Vault | Out-Null
$vaultPath = (Resolve-Path $Vault).Path

if ($Rebuild -or -not (docker images -q kbmd)) {
    docker build -t kbmd $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "kbmd: $vaultPath  ->  http://localhost:$Port"
docker run --rm -it -p "127.0.0.1:${Port}:8787" -v "${vaultPath}:/vault" kbmd
