param([string]$Java = 'java', [string]$ClassName = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$failures = @()
foreach ($module in @('backend', 'etl')) {
    $base = Join-Path $root "server/$module"
    $dependencies = Join-Path $base 'target/regression-classpath.txt'
    if (-not (Test-Path -LiteralPath $dependencies)) { throw "Run Maven test-compile and dependency:build-classpath first: $dependencies" }
    $classpath = (Join-Path $base 'target/test-classes') + ';' + (Join-Path $base 'target/classes') + ';' + [IO.File]::ReadAllText($dependencies).Trim()
    $options = @('-ea')
    if ($module -eq 'etl') {
        $options += @('-Dspark.master=local[2]', '-Dspark.ui.enabled=false', '-Dio.netty.tryReflectionSetAccessible=true', '--add-modules=jdk.incubator.vector')
        foreach ($package in @('java.lang', 'java.lang.invoke', 'java.lang.reflect', 'java.io', 'java.net', 'java.nio', 'java.util', 'java.util.concurrent', 'java.util.concurrent.atomic', 'sun.nio.ch', 'sun.nio.cs', 'sun.security.action', 'sun.util.calendar')) {
            $options += "--add-opens=java.base/$package=ALL-UNNAMED"
        }
    }
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $base 'src/test/java') -Recurse -Filter '*Regression.java') {
        $text = [IO.File]::ReadAllText($file.FullName)
        $package = [regex]::Match($text, '(?m)^package ([\w.]+);').Groups[1].Value
        $class = $package + '.' + $file.BaseName
        if ($ClassName -and $class -ne $ClassName) { continue }
        $arguments = @()
        if ($file.BaseName -eq 'WarehouseLayersRegression') {
            $warehouse = Join-Path ([IO.Path]::GetTempPath()) ('hotspot-warehouse-' + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $warehouse -Force | Out-Null
            $arguments += [uri]::UnescapeDataString(([uri]$warehouse).AbsoluteUri)
        }
        Write-Host "Running $class"
        $ErrorActionPreference = 'Continue'
        & $Java @options -cp $classpath $class @arguments
        $exit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($exit -ne 0) { $failures += $class }
    }
}
if ($failures.Count) { throw ('Failed regressions: ' + ($failures -join ', ')) }
Write-Host 'All Java regression entry points passed.'
