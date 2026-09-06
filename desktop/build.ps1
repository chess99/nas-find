param([ValidateSet('build','dev','test','check')][string]$Action = 'build')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$nasCargo = Join-Path $env:USERPROFILE '.cargo\bin'
$env:Path = "$nasCargo;$env:Path"
# Optional portable Microsoft tools, kept outside Git. An installed VS toolchain also works.
$nasPortable = Join-Path $PSScriptRoot '..\.local\msvc'
if (Test-Path -LiteralPath $nasPortable) {
    $nasVc = Get-ChildItem -LiteralPath (Join-Path $nasPortable 'VC\Tools\MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $nasSdk = Get-ChildItem -LiteralPath (Join-Path $nasPortable 'Windows Kits\10\Lib') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $nasKits = Join-Path $nasPortable 'Windows Kits\10'
    $env:Path = "$($nasVc.FullName)\bin\Hostx64\x64;$nasKits\bin\$($nasSdk.Name)\x64;$env:Path"
    $env:LIB = "$($nasVc.FullName)\lib\x64;$($nasSdk.FullName)\ucrt\x64;$($nasSdk.FullName)\um\x64"
    $env:INCLUDE = "$($nasVc.FullName)\include;$nasKits\Include\$($nasSdk.Name)\ucrt;$nasKits\Include\$($nasSdk.Name)\shared;$nasKits\Include\$($nasSdk.Name)\um"
}
switch ($Action) {
    'test' { & cargo test --manifest-path src-tauri/Cargo.toml }
    'check' { & cargo check --manifest-path src-tauri/Cargo.toml }
    default { & npm.cmd run $Action }
}
exit $LASTEXITCODE
