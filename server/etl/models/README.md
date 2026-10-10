# Erlangshen Sentiment Model

`Erlangshen-Roberta-330M-Sentiment` runs only on the ETL host that has the
local Python environment and GPU. It is intentionally not copied to the Spark
VMs because the model is larger than 2 GB and the VMs have limited memory.

The backend runs `etl/scripts/infer_sentiment.py` locally against the complete
CSV or its appended delta. The script writes a small UTF-8 sidecar containing:

```text
platform,content_id,sentiment_label,sentiment_positive_score,sentiment_neutral_score,sentiment_negative_score
```

Only the source CSV and sidecar are uploaded to HDFS. Spark joins the sidecar
by `platform + content_id`; the original CSV schema is unchanged.

Required local files:

```text
etl/models/Erlangshen-Roberta-330M-Sentiment/config.json
etl/models/Erlangshen-Roberta-330M-Sentiment/vocab.txt
etl/models/Erlangshen-Roberta-330M-Sentiment/pytorch_model.bin
```

Configure the local Python executable with `VM_ETL_SENTIMENT_PYTHON` and the
model directory with `VM_ETL_SENTIMENT_MODEL`. The default batch size is 32.
