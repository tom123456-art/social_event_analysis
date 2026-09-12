$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$BatchId = Get-Date -Format "yyyyMMddHHmmss"
$JarPath = Join-Path $ProjectRoot "etl\target\social-hotspot-etl-1.0.0-SNAPSHOT.jar"
$InputPath = Join-Path $ProjectRoot "data\crawler\social_event_real.csv"

spark-submit `
  --class com.social.hotspot.etl.SocialHotspotEtlJob `
  --master local[2] `
  $JarPath `
  --input $InputPath `
  --event-id public_rss_latest `
  --event-name 自部署网站评论传播分析 `
  --batch-id $BatchId `
  --jdbc-url "jdbc:mysql://127.0.0.1:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" `
  --jdbc-user root `
  --jdbc-password "$env:DB_PASSWORD"
