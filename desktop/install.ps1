param([switch]$ImportExistingConnection, [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'NAS Find'))
$ErrorActionPreference = 'Stop'
$nasInstaller = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src-tauri\target\release\bundle\nsis') -Filter '*-setup.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $nasInstaller) { throw '请先执行 .\build.ps1 build' }
$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
if ($InstallDirectory.Contains('"')) { throw '安装目录包含无效字符' }
# NSIS requires /D to be the last argument, unquoted even when its value contains spaces.
$nasInstall = Start-Process -FilePath $nasInstaller.FullName -ArgumentList @('/S', "/D=$InstallDirectory") -WindowStyle Hidden -PassThru -Wait
if ($nasInstall.ExitCode -ne 0) { throw "安装失败：$($nasInstall.ExitCode)" }
$nasExe = Join-Path $InstallDirectory 'nas-find-desktop.exe'
if (-not (Test-Path -LiteralPath $nasExe)) { throw "安装程序已结束，但未找到 $nasExe" }
if ($ImportExistingConnection) {
    $nasAccessFile = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\.local\access.json')).Path
    Start-Process -FilePath $nasExe -ArgumentList @('--import-connection', ('"' + $nasAccessFile + '"')) -WindowStyle Hidden | Out-Null
}
Write-Output "已安装：$nasExe"
