# HanLP Weibo Sentiment Model

The Spark ETL no longer uses keyword matching for `WEIBO` records. It executes
HanLP `NaiveBayesClassifier` inference in the executor-side UDF and writes one
result per Weibo record to `dwd_weibo_sentiment_result`.

## Train the model

The supplied `pos60000.txt` and `neg60000.txt` corpus files are
GB18030-encoded, with one Weibo record per line. Train directly from the two
files; they must not be treated as two whole-document samples.

```bash
java -cp social-hotspot-etl-1.0.0-SNAPSHOT.jar \
  com.social.hotspot.etl.HanlpSentimentModelTrainer \
  --positive-file /opt/apps/social-hotspot-analytics/sentiment-corpus/pos60000.txt \
  --negative-file /opt/apps/social-hotspot-analytics/sentiment-corpus/neg60000.txt \
  --encoding GB18030 \
  --output /opt/apps/social-hotspot-analytics/etl/models/weibo-sentiment.bin
```

This produces a positive/negative model. The deployed version also adds a domain-calibrated neutral set extracted from factual, waiting-for-update, and non-committal Weibo comments, producing a three-class model. The ETL preserves the existing
three-label database contract by emitting `neutral` only for low-confidence or
near-tie predictions. You can provide `--neutral-file` later if manually
labeled neutral comments become available.

## Run the ETL

Pass the model to Spark with `--files`, then pass its distributed file name to
the job. The provided `deploy/scripts/run-vm-etl.sh` does this automatically.

```bash
spark-submit --files /models/weibo-sentiment.bin#weibo-sentiment.bin ... \
  --sentiment-model weibo-sentiment.bin
```

`VM_ETL_SENTIMENT_MODEL` must point to the local model binary when the ETL is
started from the web application. The backend uploads that file to the VM and
adds it to the Spark submission.
