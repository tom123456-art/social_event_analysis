# Erlangshen Sentiment Model

`Erlangshen-Roberta-330M-Sentiment` runs only on the ETL host that has the
local Python environment and GPU. It is intentionally not copied to the Spark
VMs because the model is larger than 2 GB and the VMs have limited memory.

The backend runs `server/etl/scripts/infer_sentiment.py` locally against the complete
CSV or its appended delta. The script writes a small UTF-8 sidecar containing:

```text
platform,content_id,sentiment_label,sentiment_positive_score,sentiment_neutral_score,sentiment_negative_score
```

Only the source CSV and sidecar are uploaded to HDFS. Spark joins the sidecar
by `platform + content_id`; the original CSV schema is unchanged.

Global text quality rules are shared in `src/main/resources/text-quality-rules.json`.
Python skips damaged display fields before inference. Spark checks original fields
before normalization and excludes damaged records before all DWD/ADS aggregation,
not just sentiment analysis. `DataQualityTextEncoding` logs the filter counts.
Rejected original records and reasons are stored under
`warehouse/quarantine/text_encoding/batch_id=...`; Raw CSV remains unchanged.
The conservative rules preserve ordinary question marks, English and emoji;
undetected damage remains possible and the shared fixtures should grow with
confirmed cases. Policy changes invalidate the incremental baseline.

Required local files:

```text
server/etl/models/Erlangshen-Roberta-330M-Sentiment/config.json
server/etl/models/Erlangshen-Roberta-330M-Sentiment/vocab.txt
server/etl/models/Erlangshen-Roberta-330M-Sentiment/pytorch_model.bin
```

Configure the local Python executable with `VM_ETL_SENTIMENT_PYTHON` and the
model directory with `VM_ETL_SENTIMENT_MODEL`. The configured default batch size is 128.

Labels come exclusively from the model's two outputs (negative and positive).
The tokenizer loads the local vocabulary using the installed API and validates
its size, Chinese tokens and model compatibility before inference. News input is
the title plus body, truncated to 256 tokens; Weibo input is comment text.
No keywords override them and no neutral class is inferred. The sidecar contains
all predictions, including low-confidence rows, so missing inference is not
mistaken for deliberate filtering. The legacy neutral-score column is always 0
for schema compatibility, not a third probability.

Spark admits a cleaned record only when the winning model score is at least
`VM_ETL_SENTIMENT_MIN_CONFIDENCE` (default 0.90). Invalid probabilities or missing
predictions fail the batch; low-confidence predictions are filtered before DWD,
ADS and MySQL. The source CSV is preserved. Filter counts are recorded as the
input/output difference of the `SentimentConfidenceFilter` task and included in
the batch's discarded/dirty count. Every downstream chart uses the filtered
population; proportions must not be interpreted as proportions of all raw data.
High confidence is not measured accuracy and does not guarantee that objectively
neutral reporting is excluded by a binary model.

Use a fresh sidecar path after changing inference logic. `--resume` is only allowed
when the binary-policy metadata, input hash and model path match. A new policy or
threshold requires a full rebuild to establish a consistent incremental baseline.
