import argparse
import csv
import importlib.metadata
import os
import sys
from pathlib import Path


# Local/offline inference works with the installed hub APIs, while the current
# transformers package rejects the newer hub version during its import check.
_metadata_version = importlib.metadata.version
importlib.metadata.version = (
    lambda name: "1.3.0" if name == "huggingface-hub" else _metadata_version(name)
)

import pandas as pd
import torch
from transformers import BertForSequenceClassification, BertTokenizer


NEUTRAL_MARKERS = (
    "观望", "等通报", "等后续", "等消息", "不急着", "先看看", "先了解",
    "理性看待", "不站队", "不评价", "持续关注", "让子弹飞", "看情况",
    "等事实", "等官方", "等更多细节", "后续再说", "再看看", "不急着下结论",
    "先收藏", "已收藏", "持续跟进",
)
NEGATIVE_MARKERS = (
    "气愤", "失望", "心痛", "心寒", "无语", "破防", "心累", "气死", "愤怒",
    "难过", "恶心", "伤心", "太离谱", "无法接受", "必须严查", "严惩", "追责",
    "不能不了了之", "必须给个说法", "不支持", "不满意", "不开心",
)
POSITIVE_MARKERS = (
    "爱了", "支持", "给力", "漂亮", "稳了", "好消息", "欣慰", "点赞", "认可",
    "期待", "希望越来越好", "好评", "太棒", "开心", "正能量", "心情好了",
    "经济向好", "正确的做法", "该有的样子", "值得肯定", "加油",
)
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
    parser.add_argument("--neutral-confidence", type=float, default=0.65)
    return parser.parse_args()


def has_any(text, markers):
    return any(marker in text for marker in markers)


def final_label(text, raw_label, confidence, neutral_confidence):
    # Negative phrases go first so "不支持" cannot match "支持".
    if has_any(text, NEGATIVE_MARKERS):
        return "negative"
    if has_any(text, POSITIVE_MARKERS):
        return "positive"
    if has_any(text, NEUTRAL_MARKERS) or confidence < neutral_confidence:
        return "neutral"
    return raw_label


def main():
    args = parse_args()
    model_dir = Path(args.model).resolve()
    if not model_dir.is_dir():
        raise FileNotFoundError(f"Model directory does not exist: {model_dir}")

    frame = pd.read_csv(
        args.input,
        encoding="utf-8-sig",
        usecols=lambda name: name in {"platform", "content_id", "title", "content_text"},
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
    if frame.empty:
        pd.DataFrame(columns=columns).to_csv(output_path, index=False, encoding="utf-8")
        print("sentiment_rows=0")
        return

    tokenizer = BertTokenizer(vocab_file=str(model_dir / "vocab.txt"), do_lower_case=True)
    model = BertForSequenceClassification.from_pretrained(model_dir, local_files_only=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device).eval()
    if device.type == "cuda":
        torch.set_float32_matmul_precision("high")
    batch_size = args.batch_size if device.type == "cuda" else min(args.batch_size, 8)

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
        platforms = chunk["platform"].tolist()
        with torch.inference_mode():
            for start in range(0, len(texts), batch_size):
                batch_texts = texts[start:start + batch_size]
                encoded = tokenizer(batch_texts, padding=True, truncation=True, max_length=256, return_tensors="pt")
                encoded = {name: value.to(device) for name, value in encoded.items()}
                probabilities = torch.softmax(model(**encoded).logits.float(), dim=-1).cpu()
                for offset, (text, probability) in enumerate(zip(batch_texts, probabilities)):
                    negative = float(probability[0])
                    positive = float(probability[1])
                    raw_label = "positive" if positive >= negative else "negative"
                    platform = platforms[start + offset]
                    label = final_label(text, raw_label, max(positive, negative), args.neutral_confidence) if platform == "WEIBO" else (raw_label if max(positive, negative) >= args.neutral_confidence else "neutral")
                    neutral = max(0.0, 1.0 - max(positive, negative)) if label == "neutral" else 0.0
                    results.append((label, positive, neutral, negative))
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
