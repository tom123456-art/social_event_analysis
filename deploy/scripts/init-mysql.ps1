$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$SchemaPath = Join-Path $ProjectRoot "deploy\sql\schema.sql"
$MysqlExe = "D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$MysqlPassword = $env:MYSQL_PASSWORD

if (-not (Test-Path $MysqlExe)) {
  $MysqlExe = "mysql"
}

& $MysqlExe `
  --host=127.0.0.1 `
  --user=root `
  $(if ([string]::IsNullOrWhiteSpace($MysqlPassword)) { "--password" } else { "--password=$MysqlPassword" }) `
  --default-character-set=utf8mb4 `
  --execute="source $SchemaPath"
