import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
"""Read-only candidate audit; does not infer sentiment or modify source data."""
import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from hotspot_etl.quality.html_text import clean_html
from hotspot_etl.quality.sentiment_gate import eligibility, POLICY
from hotspot_etl.quality.text_quality import record_rejection_reason


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--model", required=True)
    args = parser.parse_args()
    from hotspot_etl.inference.sentiment_inference import load_tokenizer
    tokenizer = load_tokenizer(Path(args.model))
    before = hashlib.sha256(Path(args.input).read_bytes()).hexdigest()
    stats, samples = defaultdict(Counter), defaultdict(list)
    total = 0
    with open(args.input, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            total += 1
            platform = row.get("platform", "").strip().upper()
            if record_rejection_reason(row):
                stats[platform]["text_damage"] += 1
                continue
            title, body = (clean_html(row.get(field, "")) for field in ("title", "content_text"))
            text = body if platform == "WEIBO" else (title + "。" + body).strip("。 ")
            # A missing full-text cue cannot reappear after truncation.
            keep, reason = eligibility(platform, text)
            if keep:
                window = tokenizer.decode(tokenizer.encode(text, add_special_tokens=False,
                                          truncation=True, max_length=254), skip_special_tokens=True)
                keep, reason = eligibility(platform, window)
            status = "candidate" if keep else "skipped"
            stats[platform][status] += 1
            key = platform + ":" + status
            if len(samples[key]) < 3:
                samples[key].append(dict(content_id=row.get("content_id"), title=title[:100],
                                         text=body[:160], reason=reason))
            if total % 20000 == 0:
                print("scanned=" + str(total), flush=True)
    after = hashlib.sha256(Path(args.input).read_bytes()).hexdigest()
    if before != after:
        raise RuntimeError("Source CSV changed during audit; discard this audit")
    print(json.dumps(dict(policy=POLICY, source_records=total, source_sha256=before,
                         note="Raw-row gate audit only; not deduplicated, not a complete quality pipeline or model result",
                         platforms=stats, samples=samples), ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
