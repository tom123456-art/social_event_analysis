#!/bin/bash
set -e

SPARK_HOME=/opt/bigdata/spark
APP_HOME=/opt/apps/social-hotspot-analytics
BATCH_ID=$(date +%Y%m%d%H%M%S)
RAW_INPUT="$APP_HOME/data/crawler/social_event_real.csv"

cd "$SPARK_HOME/bin"
./spark-submit \
  --class com.social.hotspot.etl.SocialHotspotEtlJob \
  --master spark://192.168.154.121:7077 \
  --driver-memory 768m \
  "$APP_HOME/etl/target/social-hotspot-etl-1.0.0-SNAPSHOT.jar" \
  --input "file://$RAW_INPUT" \
  --event-id public_rss_latest \
  --event-name 自部署网站评论传播分析 \
  --batch-id "$BATCH_ID" \
  --jdbc-url "jdbc:mysql://192.168.154.121:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  --jdbc-user root \
  --jdbc-password "${DB_PASSWORD:?请先设置 DB_PASSWORD}"
