#!/bin/bash
set -e

# The large Erlangshen model stays on the Windows ETL host. This VM script
# only consumes the CSV and the small sidecar produced by local inference.
SPARK_HOME=/opt/bigdata/spark
APP_HOME=/opt/apps/social-hotspot-analytics
BATCH_ID=$(date +%Y%m%d%H%M%S)
RAW_INPUT="$APP_HOME/data/crawler/social_event_real.csv"
SENTIMENT_INPUT="${SENTIMENT_INPUT:-$APP_HOME/data/crawler/sentiment_result.csv}"

if [ ! -f "$SENTIMENT_INPUT" ]; then
  echo "未找到本批次情感分析结果：$SENTIMENT_INPUT" >&2
  exit 1
fi

cd "$SPARK_HOME/bin"
./spark-submit \
  --class com.social.hotspot.etl.SocialHotspotEtlJob \
  --master spark://192.168.154.121:7077 \
  --driver-memory 768m \
  "$APP_HOME/server/etl/target/etl-1.0.0-SNAPSHOT.jar" \
  --input "file://$RAW_INPUT" \
  --sentiment-input "file://$SENTIMENT_INPUT" \
  --event-id public_rss_latest \
  --event-name "Social Media Hotspot Event Propagation Analysis" \
  --batch-id "$BATCH_ID" \
  --sentiment-min-confidence "${SENTIMENT_MIN_CONFIDENCE:-0.90}" \
  --jdbc-url "jdbc:mysql://192.168.154.121:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  --jdbc-user root \
  --jdbc-password "${DB_PASSWORD:?请先设置 DB_PASSWORD 环境变量}"
