$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$project = $PSScriptRoot
Set-Location -LiteralPath $project
function Note($s) { Write-Host "[Project Hub] $s" -ForegroundColor Cyan }
function Good($s) { Write-Host "[Project Hub] $s" -ForegroundColor Green }
function Fail($s) { throw $s }

function Find-Jdk {
    $paths = New-Object 'System.Collections.Generic.List[string]'
    if ($env:JAVA_HOME) { $paths.Add($env:JAVA_HOME) }
    foreach ($name in @('javac.exe','java.exe')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { $paths.Add((Split-Path (Split-Path $cmd.Source -Parent) -Parent)) }
    }
    foreach ($root in @('C:\Program Files\Java','C:\Program Files\Eclipse Adoptium','C:\Program Files\Microsoft','C:\Program Files\Amazon Corretto','C:\Program Files\Zulu','C:\Program Files\BellSoft',(Join-Path $project 'tools'))) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object { $paths.Add($_.FullName) }
        }
    }
    foreach ($path in ($paths | Select-Object -Unique)) {
        $java = Join-Path $path 'bin\java.exe'
        $javac = Join-Path $path 'bin\javac.exe'
        if (-not ((Test-Path $java) -and (Test-Path $javac))) { continue }
        # javac -version writes to stdout, avoiding Windows PowerShell's java -version stderr issue.
        $versionText = (& $javac -version 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) { continue }
        if ($versionText -match 'javac\s+(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17) { return $path }
        }
    }
    Fail 'JDK 17 or later is required. Install JDK 17/21 first (not just a JRE).'
}

function Find-Tomcat {
    $paths = New-Object 'System.Collections.Generic.List[string]'
    if ($env:CATALINA_HOME) { $paths.Add($env:CATALINA_HOME) }
    foreach ($root in @('C:\Program Files\Apache Software Foundation','C:\Program Files\Apache Tomcat',(Join-Path $env:USERPROFILE 'Desktop'),(Join-Path $env:USERPROFILE 'Downloads'),(Join-Path $env:USERPROFILE 'Documents'),(Join-Path $project 'tools'))) {
        if (-not (Test-Path $root)) { continue }
        # Look in nested extracted ZIP folders, but avoid scanning an entire disk.
        $q = New-Object System.Collections.Queue
        $q.Enqueue(@($root,0))
        while ($q.Count -gt 0) {
            $node = $q.Dequeue(); $folder = [string]$node[0]; $depth = [int]$node[1]
            if (Test-Path (Join-Path $folder 'bin\catalina.bat')) { $paths.Add($folder); continue }
            if ($depth -ge 4) { continue }
            foreach ($child in @(Get-ChildItem -LiteralPath $folder -Directory -Force -ErrorAction SilentlyContinue)) {
                if ($child.Name -in @('.git','node_modules','AppData','build','.gradle','target')) { continue }
                $q.Enqueue(@($child.FullName,$depth+1))
            }
        }
    }
    foreach ($path in ($paths | Select-Object -Unique)) {
        if ((Test-Path (Join-Path $path 'bin\catalina.bat')) -and
            ((Test-Path (Join-Path $path 'lib\servlet-api.jar')) -or (Test-Path (Join-Path $path 'lib\jakarta.servlet-api.jar')))) { return $path }
    }
    Fail 'Apache Tomcat 10.1 was not found. Extract Tomcat 10.1 under Desktop, Documents, or Downloads.'
}

function Check-Exit($label) { if ($LASTEXITCODE -ne 0) { Fail "$label failed (exit code $LASTEXITCODE)." } }

try {
    Note 'Checking installed Java and Tomcat...'
    $jdk = Find-Jdk
    $tomcat = Find-Tomcat
    $env:JAVA_HOME = $jdk
    $env:JRE_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
    Good "Java: $jdk"
    Good "Tomcat: $tomcat"

    $driver = @(Get-ChildItem (Join-Path $project 'lib') -Filter 'mysql-connector-j*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($driver.Count -eq 0) { Fail 'Missing bundled MySQL JDBC driver in lib.' }
    foreach ($needed in @('src\DatabaseBootstrap.java','src\ProjectApp.java','database.sql','web\WEB-INF\web.xml')) {
        if (-not (Test-Path (Join-Path $project $needed))) { Fail "Missing project file: $needed" }
    }

    Write-Host ''
    $username = Read-Host 'MySQL username [root]'
    if ([string]::IsNullOrWhiteSpace($username)) { $username = 'root' }
    $secret = Read-Host 'MySQL password' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    $env:PM_DB_USER = $username
    $env:PM_DB_PASS = $password
    $password = $null
    $secret = $null

    $build = Join-Path $project 'build'
    $classes = Join-Path $build 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $java = Join-Path $jdk 'bin\java.exe'
    $javac = Join-Path $jdk 'bin\javac.exe'
    $jar = $driver[0].FullName
    $servletApi = Join-Path $tomcat 'lib\servlet-api.jar'
    if (-not (Test-Path $servletApi)) { $servletApi = Join-Path $tomcat 'lib\jakarta.servlet-api.jar' }
    Note 'Compiling project...'
    & $javac '-encoding' 'UTF-8' '-cp' "$jar;$servletApi" '-d' $classes (Join-Path $project 'src\DatabaseBootstrap.java') (Join-Path $project 'src\ProjectApp.java')
    Check-Exit 'Java compilation'

    Note 'Verifying MySQL credentials and setting up database...'
    & $java '-cp' "$classes;$jar" 'DatabaseBootstrap'
    Check-Exit 'MySQL login/database initialization'
    Good 'MySQL login accepted. Database is ready.'

    # Separate Tomcat instance, so we never overwrite the user's existing webapps.
    $base = Join-Path $build 'tomcat'
    foreach ($dir in @('conf','webapps','logs','temp','work')) { New-Item -ItemType Directory -Path (Join-Path $base $dir) -Force | Out-Null }
    Copy-Item -Path (Join-Path $tomcat 'conf\*') -Destination (Join-Path $base 'conf') -Recurse -Force
    $app = Join-Path $base 'webapps\ProjectHub'
    if (Test-Path $app) { Remove-Item -LiteralPath $app -Recurse -Force }
    New-Item -ItemType Directory -Path (Join-Path $app 'WEB-INF\classes') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $app 'WEB-INF\lib') -Force | Out-Null
    Copy-Item -Path (Join-Path $project 'web\*') -Destination $app -Recurse -Force
    Copy-Item -Path (Join-Path $classes '*') -Destination (Join-Path $app 'WEB-INF\classes') -Recurse -Force
    Copy-Item -LiteralPath $jar -Destination (Join-Path $app 'WEB-INF\lib') -Force

    $env:CATALINA_HOME = $tomcat
    $env:CATALINA_BASE = $base
    function Port-InUse([int]$port) {
        $client = New-Object Net.Sockets.TcpClient
        try {
            $connection = $client.BeginConnect('127.0.0.1',$port,$null,$null)
            if ($connection.AsyncWaitHandle.WaitOne(400)) {
                try { $client.EndConnect($connection); return $true } catch { return $false }
            }
            return $false
        } finally { $client.Close() }
    }
    $port = 8080
    while (Port-InUse $port) {
        $port++
        if ($port -gt 8090) { Fail 'No available web port from 8080 to 8090.' }
    }
    # Keep Tomcat's separate instance from competing for the default shutdown port.
    $serverFile = Join-Path $base 'conf\server.xml'
    $serverXml = [System.IO.File]::ReadAllText($serverFile)
    $serverXml = [regex]::Replace($serverXml,'(<Server\s+port=")[^"]+','${1}-1')
    $serverXml = [regex]::Replace($serverXml,'(<Connector\s+port=")8080','${1}' + $port)
    [System.IO.File]::WriteAllText($serverFile,$serverXml)

    Note 'Starting Tomcat and website...'
    $startup = Join-Path $tomcat 'bin\startup.bat'
    & $startup
    Check-Exit 'Tomcat startup'
    $url = "http://localhost:$port/ProjectHub/"
    $ready = $false
    for ($i=0; $i -lt 45; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200 -and $r.Content -match 'Project Hub') { $ready = $true; break }
        } catch {}
    }
    if (-not $ready) { Fail "Website did not become ready. Check logs in $base\logs" }
    Good 'Website is running!'
    Start-Process $url
    Write-Host "Opened: $url" -ForegroundColor Green
    Write-Host 'Keep MySQL Server running while using the website.'
}
catch {
    Write-Host "`nSETUP ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
finally {
    Remove-Item Env:PM_DB_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:PM_DB_USER -ErrorAction SilentlyContinue
}
