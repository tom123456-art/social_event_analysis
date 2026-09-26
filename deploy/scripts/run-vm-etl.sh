#!/bin/bash
set -e

SPARK_HOME=/opt/bigdata/spark
APP_HOME=/opt/apps/social-hotspot-analytics
BATCH_ID=$(date +%Y%m%d%H%M%S)
RAW_INPUT="$APP_HOME/data/crawler/social_event_real.csv"
SENTIMENT_MODEL="${SENTIMENT_MODEL:-$APP_HOME/etl/models/weibo-sentiment.bin}"

if [ ! -f "$SENTIMENT_MODEL" ]; then
  echo "HanLP sentiment model not found: $SENTIMENT_MODEL" >&2
  exit 1
fi

cd "$SPARK_HOME/bin"
./spark-submit \
  --class com.social.hotspot.etl.SocialHotspotEtlJob \
  --master spark://192.168.154.121:7077 \
  --driver-memory 768m \
  "$APP_HOME/etl/target/social-hotspot-etl-1.0.0-SNAPSHOT.jar" \
  --input "file://$RAW_INPUT" \
  --event-id public_rss_latest \
  --event-name "Social Media Hotspot Event Propagation Analysis" \
  --batch-id "$BATCH_ID" \
  --jdbc-url "jdbc:mysql://192.168.154.121:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  --jdbc-user root \
  --jdbc-password "${DB_PASSWORD:?DB_PASSWORD must be set}" \
  --sentiment-model "$SENTIMENT_MODEL"
