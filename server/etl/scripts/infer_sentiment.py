import argparse
import csv
import importlib.metadata
import hashlib
import json
import inspect
import os
import sys
from pathlib import Path
from text_quality import FIELDS, VERSION as TEXT_QUALITY_VERSION, record_rejection_reason


# Local/offline inference works with the installed hub APIs, while the current
# transformers package rejects the newer hub version during its import check.
_metadata_version = importlib.metadata.version
importlib.metadata.version = (
    lambda name: "1.3.0" if name == "huggingface-hub" else _metadata_version(name)
)

import pandas as pd
import torch
from transformers import BertForSequenceClassification, BertTokenizer


SUPPORTED_PLATFORMS = {
    "WEIBO", "SOHU_NEWS", "TENCENT_NEWS", "NETEASE_NEWS", "SINA_NEWS", "THE_PAPER",
}


def parse_args():
    parser = argparse.ArgumentParser(description="Offline Erlangshen sentiment inference")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--chunk-size", type=int, default=4096)
    parser.add_argument("--resume", action="store_true", help="Resume only matching binary inference output")
    return parser.parse_args()


def model_label(negative, positive):
    return "positive" if positive >= negative else "negative"


def load_tokenizer(model_dir):
    vocab_path = model_dir / "vocab.txt"
    # Some vocabulary tokens contain Unicode line separators; only LF delimits IDs.
    vocabulary = vocab_path.read_text(encoding="utf-8").rstrip("\n").split("\n")
    if "vocab" in inspect.signature(BertTokenizer).parameters:
        tokenizer = BertTokenizer(vocab={token: index for index, token in enumerate(vocabulary)}, do_lower_case=True)
    else:
        tokenizer = BertTokenizer(vocab_file=str(vocab_path), do_lower_case=True)
    probe = tokenizer.tokenize("\u4eca\u5929\u5fc3\u60c5\u5f88\u597d")
    if len(tokenizer) != len(vocabulary) or not probe or all(token == tokenizer.unk_token for token in probe):
        raise ValueError("Tokenizer vocabulary was not loaded correctly; refusing inference")
    print(f"tokenizer_vocab_size={len(tokenizer)} tokenizer_probe={probe}", flush=True)
    return tokenizer


def main():
    args = parse_args()
    model_dir = Path(args.model).resolve()
    if not model_dir.is_dir():
        raise FileNotFoundError(f"Model directory does not exist: {model_dir}")

    frame = pd.read_csv(
        args.input,
        encoding="utf-8-sig",
        usecols=lambda name: name in {"platform", "content_id", *FIELDS},
        dtype=str,
        keep_default_na=False,
    )
    required = {"platform", "content_id", "content_text"}
    if not required.issubset(frame.columns):
        missing = sorted(required.difference(frame.columns))
        raise ValueError(f"Raw CSV is missing sentiment input columns: {missing}")

    frame["platform"] = frame["platform"].str.strip().str.upper()
    frame["content_id"] = frame["content_id"].str.strip()
    if "title" not in frame.columns:
        frame["title"] = ""
    frame["title"] = frame["title"].str.strip()
    frame["content_text"] = frame["content_text"].str.strip()
    text_damage = frame.apply(record_rejection_reason, axis=1)
    print(f"text_quality_policy={TEXT_QUALITY_VERSION} text_damage_discarded={text_damage.notna().sum()}", flush=True)
    frame = frame[text_damage.isna()].copy()
    frame["model_text"] = frame["content_text"]
    news_mask = frame["platform"] != "WEIBO"
    frame.loc[news_mask, "model_text"] = (frame.loc[news_mask, "title"] + "。" + frame.loc[news_mask, "content_text"]).str.strip("。 ")
    frame = frame[
        (frame["platform"].isin(SUPPORTED_PLATFORMS))
        & (frame["content_id"].str.len() > 0)
        & (frame["model_text"].str.len() > 0)
    ].drop_duplicates(["platform", "content_id"], keep="last").reset_index(drop=True)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    columns = [
        "platform", "content_id", "sentiment_label", "sentiment_positive_score",
        "sentiment_neutral_score", "sentiment_negative_score",
    ]
    metadata_path = output_path.with_suffix(output_path.suffix + ".metadata.json")
    with open(args.input, "rb") as input_file:
        input_hash = hashlib.file_digest(input_file, "sha256").hexdigest()
    metadata = {
        "policy": "MODEL_BINARY_V2_VALIDATED_VOCAB",
        "text_quality_policy": TEXT_QUALITY_VERSION,
        "input_sha256": input_hash,
        "model": str(model_dir),
        "max_length": 256,
    }
    if output_path.exists() and output_path.stat().st_size > 0:
        if not args.resume or not metadata_path.exists() or json.loads(metadata_path.read_text(encoding="utf-8")) != metadata:
            raise ValueError("Output already exists or resume metadata differs; use a new output file for fresh binary inference")
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=True, indent=2), encoding="utf-8")
    if frame.empty:
        pd.DataFrame(columns=columns).to_csv(output_path, index=False, encoding="utf-8")
        print("sentiment_rows=0")
        return

    tokenizer = load_tokenizer(model_dir)
    model = BertForSequenceClassification.from_pretrained(model_dir, local_files_only=True)
    labels = {int(key): str(value).lower() for key, value in model.config.id2label.items()}
    if labels != {0: "negative", 1: "positive"}:
        raise ValueError(f"Unexpected model labels: {labels}")
    if len(tokenizer) != model.config.vocab_size:
        raise ValueError("Model and tokenizer vocabulary sizes differ")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device).eval()
    if device.type == "cuda":
        torch.set_float32_matmul_precision("high")
    batch_size = args.batch_size if device.type == "cuda" else min(args.batch_size, 8)
    print(f"policy=MODEL_BINARY_V2_VALIDATED_VOCAB sentiment_input_rows={len(frame)} device={device} batch_size={batch_size}", flush=True)

    # Persist each chunk immediately so a long run can resume after a timeout.
    completed = set()
    if output_path.exists() and output_path.stat().st_size > 0:
        existing = pd.read_csv(output_path, usecols=["platform", "content_id"], dtype=str)
        completed = set(zip(existing["platform"], existing["content_id"]))
    pending = frame[~frame.set_index(["platform", "content_id"]).index.isin(completed)].reset_index(drop=True)
    if not output_path.exists() or output_path.stat().st_size == 0:
        pd.DataFrame(columns=columns).to_csv(output_path, index=False, encoding="utf-8")

    total_written = len(completed)
    for chunk_start in range(0, len(pending), max(1, args.chunk_size)):
        chunk = pending.iloc[chunk_start:chunk_start + max(1, args.chunk_size)]
        results = []
        texts = chunk["model_text"].tolist()
        with torch.inference_mode():
            for start in range(0, len(texts), batch_size):
                batch_texts = texts[start:start + batch_size]
                encoded = tokenizer(batch_texts, padding=True, truncation=True, max_length=256, return_tensors="pt")
                encoded = {name: value.to(device) for name, value in encoded.items()}
                probabilities = torch.softmax(model(**encoded).logits.float(), dim=-1).cpu()
                for probability in probabilities:
                    negative = float(probability[0])
                    positive = float(probability[1])
                    results.append((model_label(negative, positive), positive, 0.0, negative))
        output = chunk[["platform", "content_id"]].copy()
        output["sentiment_label"] = [item[0] for item in results]
        output["sentiment_positive_score"] = [round(item[1], 6) for item in results]
        output["sentiment_neutral_score"] = [round(item[2], 6) for item in results]
        output["sentiment_negative_score"] = [round(item[3], 6) for item in results]
        output.to_csv(output_path, mode="a", header=False, index=False, encoding="utf-8", quoting=csv.QUOTE_MINIMAL)
        total_written += len(output)
        print(f"sentiment_rows={total_written}/{len(frame)} device={device}", flush=True)
    counts = pd.read_csv(output_path)["sentiment_label"].value_counts().to_dict()
    print(f"sentiment_rows={total_written} device={device} distribution={counts}")


if __name__ == "__main__":
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    sys.exit(main())
