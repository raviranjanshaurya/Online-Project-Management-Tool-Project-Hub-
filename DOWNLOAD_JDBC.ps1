$ErrorActionPreference = 'Stop'
$version = '9.5.0'
$targetDir = Join-Path $PSScriptRoot 'lib'
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
$target = Join-Path $targetDir "mysql-connector-j-$version.jar"
$url = "https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/$version/mysql-connector-j-$version.jar"
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression
    $zip = [IO.Compression.ZipFile]::OpenRead($target)
    try {
        $driver = $zip.Entries | Where-Object { $_.FullName -eq 'com/mysql/cj/jdbc/Driver.class' } | Select-Object -First 1
        if (-not $driver) { throw 'Downloaded JAR does not contain MySQL JDBC driver' }
    } finally { $zip.Dispose() }
    Write-Host "Driver installed: $target"
    exit 0
} catch {
    Remove-Item $target -ErrorAction SilentlyContinue
    Write-Host "JDBC download failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
