$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mvnCmd = $null
$found = Get-Command mvn -ErrorAction SilentlyContinue
if ($found -and (Test-Path $found.Source)) { $mvnCmd = $found.Source }
if (-not $mvnCmd) {
  $tools = Join-Path $root ".tools"
  $mavenHome = Join-Path $tools "apache-maven-3.9.11"
  $mvnCmd = Join-Path $mavenHome "bin\mvn.cmd"
  if (-not (Test-Path $mvnCmd)) {
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $zip = Join-Path $tools "maven.zip"
    curl.exe -fsSL -o $zip "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Expand-Archive -Path $zip -DestinationPath $tools -Force
  }
}
if (-not (Test-Path $mvnCmd)) { Write-Error "mvn not found"; exit 1 }
Push-Location $root
& $mvnCmd -q test
$code = $LASTEXITCODE
if ($code -eq 0 -and ($args -contains "run")) {
  & $mvnCmd spring-boot:run
  $code = $LASTEXITCODE
}
Pop-Location
exit $code
