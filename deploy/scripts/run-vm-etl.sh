#!/bin/bash
set -euo pipefail

# The large Erlangshen model stays on the Windows ETL host. This VM script
# only consumes the CSV and the small sidecar produced by local inference.
SPARK_HOME=/opt/bigdata/spark
APP_HOME=/opt/apps/social-hotspot-analytics
BATCH_ID=$(date +%Y%m%d%H%M%S)
RAW_INPUT="$APP_HOME/data/crawler/social_event_real.csv"
SENTIMENT_INPUT="${SENTIMENT_INPUT:-$APP_HOME/data/crawler/sentiment_result.csv}"
: "${DB_PASSWORD:?请先设置 DB_PASSWORD 环境变量}"

if [ ! -f "$SENTIMENT_INPUT" ]; then
  echo "未找到本批次情感分析结果：$SENTIMENT_INPUT" >&2
  exit 1
fi

cd "$SPARK_HOME/bin"
HDFS_ROOT=hdfs://192.168.154.121:8020/social-hotspot-analytics
HDFS_RAW="$HDFS_ROOT/raw/social_event/batch_id=$BATCH_ID"
hdfs dfs -mkdir -p "$HDFS_RAW/_sentiment"
hdfs dfs -put "$RAW_INPUT" "$HDFS_RAW/social_event_real.csv"
hdfs dfs -put "$SENTIMENT_INPUT" "$HDFS_RAW/_sentiment/sentiment_result.csv"
./spark-submit \
  --class com.social.hotspot.etl.job.HotspotAnalysisJob \
  --master spark://192.168.154.121:7077 \
  --driver-memory 768m \
  "$APP_HOME/server/etl/target/etl-1.0.0-SNAPSHOT.jar" \
  --input "$HDFS_RAW/social_event_real.csv" \
  --sentiment-input "$HDFS_RAW/_sentiment/sentiment_result.csv" \
  --warehouse-output "$HDFS_ROOT/warehouse" \
  --event-id public_rss_latest \
  --event-name "网络媒体热点事件传播态势与舆情分析" \
  --batch-id "$BATCH_ID" \
  --sentiment-min-confidence "${SENTIMENT_MIN_CONFIDENCE:-0.95}" \
  --jdbc-url "jdbc:mysql://192.168.154.121:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  --jdbc-user root \
  --jdbc-password "${DB_PASSWORD:?请先设置 DB_PASSWORD 环境变量}"

export HADOOP_HOME=/opt/bigdata/hadoop-client
export HADOOP_CONF_DIR="$HADOOP_HOME/etc/hadoop"
export TERM=dumb
export HADOOP_OPTS='-Djline.terminal=jline.UnsupportedTerminal -Djansi.passthrough=true -Djansi.force=false'
if [ -r /lib64/libutil.so.1 ]; then export LD_PRELOAD=/lib64/libutil.so.1; fi
BEELINE=/opt/bigdata/hive/bin/beeline
HIVE_SCHEMA=$(cat "$APP_HOME/deploy/sql/hive_tables.sql")
"$BEELINE" -u jdbc:hive2://127.0.0.1:10000/default -n root -e "$HIVE_SCHEMA"
"$BEELINE" -u jdbc:hive2://127.0.0.1:10000/default -n root -e "
ALTER TABLE social_ods.ods_social_content_raw ADD IF NOT EXISTS PARTITION (batch_id='$BATCH_ID') LOCATION '$HDFS_RAW';
ALTER TABLE social_dwd.dwd_social_content_detail ADD IF NOT EXISTS PARTITION (batch_id='$BATCH_ID') LOCATION '$HDFS_ROOT/warehouse/dwd/dwd_social_content_detail/batch_id=$BATCH_ID';"
DWS_SQL=$(hdfs dfs -cat "$HDFS_ROOT/warehouse/manifests/batch_id=$BATCH_ID/dws.sql")
"$BEELINE" -u jdbc:hive2://127.0.0.1:10000/default -n root -e "$DWS_SQL"
