param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$socketDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot '.tools/tmp'))
if (-not $socketDirectory.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Socket directory must stay inside the project'
}
[IO.Directory]::CreateDirectory($socketDirectory) | Out-Null
$previousOptions = $env:JAVA_TOOL_OPTIONS
try {
    $socketOption = '"-Djdk.net.unixdomain.tmpdir=' + $socketDirectory.Replace('\', '/') + '"'
    $env:JAVA_TOOL_OPTIONS = ($previousOptions + ' ' + $socketOption).Trim()
    Push-Location $projectRoot
    try { & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments; $result = $LASTEXITCODE }
    finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $previousOptions }
exit $result
