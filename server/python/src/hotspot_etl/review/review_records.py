"""Prepare a versioned review overlay without modifying source CSV files."""
import argparse
import csv
import hashlib
import json
import re
import unicodedata
from datetime import datetime
from pathlib import Path

from hotspot_etl.quality.text_quality import record_rejection_reason
from hotspot_etl.quality.html_text import clean_html

PLATFORMS = {"TENCENT_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "NETEASE_NEWS", "WEIBO"}
EDITABLE = {"platform", "title", "content_text", "author_name", "publish_time", "crawl_time",
            "keywords", "category", "source_url", "image_url", "parent_content_id",
            "like_count", "comment_count", "repost_count", "favorite_count"}
META = ("_review_id", "_review_approved", "_review_sentiment", "_review_hold")


def identity(row):
    source = {key: str(value or "") for key, value in row.items() if not key.startswith("_")}
    # Optional empty export columns must not change the source version.
    source = {key: value for key, value in source.items() if value}
    encoded = json.dumps(source, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    key = json.dumps([row.get(k, "") for k in ("event_id", "platform", "content_id", "content_type")])
    return hashlib.sha256(key.encode()).hexdigest(), hashlib.sha256(encoded.encode()).hexdigest()


def timestamp(value):
    try:
        return datetime.strptime(value.strip().replace("/", "-").replace("T", " "),
                                 "%Y-%m-%d %H:%M:%S" if value.strip().count(":") == 2 else "%Y-%m-%d %H:%M")
    except (ValueError, AttributeError):
        return None


def normalized_text(value):
    text = clean_html(value)
    text = "".join(c for c in text if unicodedata.category(c) not in {"Cc", "Cf"})
    text = re.sub(r"https?://\S+", " ", text, flags=re.I)
    text = re.sub(r"[A-Za-z]{24,}", " ", text)
    text = re.sub(r"([!！?？.。,，、;；:：~～_\-])\1{2,}", r"\1", text)
    return re.sub(r"\s+", " ", text).strip()[:1000]


def reasons(row):
    found = []
    if row.get("platform", "").strip().upper() not in PLATFORMS:
        found.append("UNKNOWN_PLATFORM")
    damage = record_rejection_reason(row)
    if damage:
        found.append("TEXT_DAMAGE:" + damage)
    published, crawled = timestamp(row.get("publish_time", "")), timestamp(row.get("crawl_time", ""))
    if not published or not crawled:
        found.append("INVALID_TIME")
    elif crawled < published:
        found.append("TIME_CONFLICT")
    text = row.get("content_text", "").strip() or row.get("title", "").strip()
    cleaned = normalized_text(text)
    meaningful = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", cleaned)
    if len(meaningful) < 4 or len(meaningful) * 5 < len(re.sub(r"\s+", "", cleaned)):
        found.append("SHORT_TEXT")
    if any(re.search(r"&lt;/?[A-Za-z]", row.get(k, ""), re.I) for k in ("title", "content_text")):
        found.append("ENCODED_HTML")
    if any("???" in row.get(k, "") for k in ("title", "content_text")) and not damage:
        found.append("SUSPECT_TEXT")
    if not re.fullmatch(r"[A-Za-z0-9_-]{3,128}", row.get("content_id", "").strip()):
        found.append("ID_FORMAT")
    image = row.get("image_url", "").strip()
    if image and not re.fullmatch(r"https?://\S+", image, re.I):
        found.append("IMAGE_URL")
    if re.search(r"(?:\.\.\.|\u2026)$", row.get("title", "").strip()):
        found.append("POSSIBLE_TRUNCATION")
    return found


def validate_correction(row):
    if row.get("platform", "") not in PLATFORMS:
        raise ValueError("审核后平台必须为六个标准平台之一")
    published, crawled = timestamp(row.get("publish_time", "")), timestamp(row.get("crawl_time", ""))
    if not published or not crawled or crawled < published:
        raise ValueError("审核后时间无效或采集时间早于发布时间")
    if record_rejection_reason(row):
        raise ValueError("审核后文本仍存在明确编码损坏，请先修正")
    if "ENCODED_HTML" in reasons(row):
        raise ValueError("审核后仍有编码 HTML 标签，请改为纯文本")
    text = row.get("content_text", "").strip() or row.get("title", "").strip()
    cleaned = normalized_text(text)
    meaningful = len(re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", cleaned))
    compact = len(re.sub(r"\s+", "", cleaned))
    if meaningful < 4 or meaningful * 5 < compact:
        raise ValueError("审核后有效文本不足四个字符")
    if row["platform"] == "WEIBO" and not re.search(r"[\u4e00-\u9fffA-Za-z0-9]", normalized_text(row.get("content_text", ""))):
        raise ValueError("微博审核后必须有有效正文")
    for field in ("like_count", "comment_count", "repost_count", "favorite_count"):
        value = row.get(field, "").strip()
        if value and (not re.fullmatch(r"[0-9]+", value) or len(value) > 18):
            raise ValueError("互动字段必须为空或非负整数")


def prepare(input_path, output_path, queue_path, decisions):
    overlays = {(item["source_key"], item["source_version"]): item for item in decisions}
    matched = set()
    with open(input_path, encoding="utf-8-sig", newline="") as source, \
            open(output_path, "w", encoding="utf-8", newline="") as output, \
            open(queue_path, "w", encoding="utf-8") as queue:
        reader = csv.DictReader(source)
        if not reader.fieldnames or not {"platform", "content_id", "publish_time", "crawl_time", "content_text"}.issubset(reader.fieldnames):
            raise ValueError("CSV 缺少审核输入必要字段")
        fields = [k for k in reader.fieldnames if k not in META]
        fields += sorted(EDITABLE.difference(fields))
        writer = csv.DictWriter(output, fieldnames=fields + list(META))
        writer.writeheader()
        for index, row in enumerate(reader, 1):
            if None in row or any(value is None for value in row.values()):
                raise ValueError(f"CSV 第 {index + 1} 条记录字段数量不正确")
            key, version = identity(row)
            decision = overlays.get((key, version))
            issues = reasons(row)
            if issues:
                queue.write(json.dumps({"source_key": key, "source_version": version, "source_row": index,
                                       "original_data": row, "reasons": issues}, ensure_ascii=False) + "\n")
            if decision:
                matched.add(int(decision["id"]))
                if decision["decision"] == "REJECTED":
                    continue
                correction = decision.get("corrected_data") or {}
                if not set(correction).issubset(EDITABLE):
                    raise ValueError("人工修正包含不可修改字段")
                row.update({key: "" if value is None else str(value) for key, value in correction.items()})
                validate_correction(row)
                row.update(_review_id=str(decision["id"]), _review_approved="true",
                           _review_sentiment=decision.get("manual_sentiment") or "", _review_hold="false")
            else:
                blocking = {"UNKNOWN_PLATFORM", "INVALID_TIME", "TIME_CONFLICT", "SHORT_TEXT", "ENCODED_HTML", "SUSPECT_TEXT"}
                hold = bool(blocking.intersection(issues)) or any(issue.startswith("TEXT_DAMAGE:") for issue in issues)
                row.update(_review_id="", _review_approved="false", _review_sentiment="", _review_hold=str(hold).lower())
            writer.writerow(row)
    return sorted(matched)


def collect_low(input_path, sentiment_path, queue_path, threshold):
    with open(sentiment_path, encoding="utf-8-sig", newline="") as handle:
        predictions = {(r["platform"], r["content_id"]): r for r in csv.DictReader(handle)}
    with open(input_path, encoding="utf-8-sig", newline="") as handle, open(queue_path, "w", encoding="utf-8") as output:
        for index, row in enumerate(csv.DictReader(handle), 1):
            if row.get("_review_sentiment") or row.get("_review_hold") == "true":
                continue
            result = predictions.get((row["platform"].strip().upper(), row["content_id"].strip()))
            if not result:
                continue
            if result.get("sentiment_status") == "SKIPPED":
                continue
            if max(float(result["sentiment_positive_score"]), float(result["sentiment_negative_score"])) >= threshold:
                continue
            if row.get("_review_id"):
                # Report the existing original review rather than a new corrected source version.
                output.write(json.dumps({"existing_id": int(row["_review_id"]), "reasons": ["LOW_CONFIDENCE"]}) + "\n")
            else:
                key, version = identity(row)
                original = {k: v for k, v in row.items() if not k.startswith("_")}
                output.write(json.dumps({"source_key": key, "source_version": version, "source_row": index,
                                         "original_data": original, "reasons": ["LOW_CONFIDENCE"]}, ensure_ascii=False) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output")
    parser.add_argument("--queue", required=True)
    parser.add_argument("--decisions")
    parser.add_argument("--sentiment")
    parser.add_argument("--threshold", type=float, default=.90)
    args = parser.parse_args()
    if args.sentiment:
        collect_low(args.input, args.sentiment, args.queue, args.threshold)
    else:
        decisions = json.loads(Path(args.decisions).read_text(encoding="utf-8")) if args.decisions else []
        print(json.dumps({"matched_ids": prepare(args.input, args.output, args.queue, decisions)}))


if __name__ == "__main__":
    main()
