"""Refine a small set of low-value momentum terms in the latest 14 days."""
import csv
import json
import shutil
from datetime import datetime, timedelta
from pathlib import Path

import clean_csv_keywords as topics

REPLACEMENTS = {
    "赛事": "拉力赛",
    "供热管网维护": "爆管",
    "爆管": "供热",
    "供热": "管道",
}


def date_of(row):
    value = row.get("publish_time", "").strip().replace("T", " ")
    for fmt in ("%Y/%m/%d %H:%M", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(value, fmt).date()
        except ValueError:
            continue
    return None


def main():
    source = Path("data/crawler/social_event_real.csv")
    run = Path("runtime/keyword-refinement") / datetime.now().strftime("%Y%m%d-%H%M%S")
    run.mkdir(parents=True)
    backup = run / "original.csv"
    shutil.copy2(source, backup)
    fields, rows = topics.read_csv(backup)
    latest = max(date_of(row) for row in rows if row["platform"].strip().upper() in topics.NEWS and date_of(row))
    start = latest - timedelta(days=13)
    changes = []
    output = run / "patched.csv"
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            old = row["keywords"].split(",")
            new = list(dict.fromkeys(REPLACEMENTS.get(word, word) for word in old))
            date = date_of(row)
            title = row["title"]
            if "电梯" in old:
                term = "指控" if "虚构" in title or "猥亵" in title else "住宅"
                new = [term if word == "电梯" else word for word in new]
            if "加装" in old and ("住宅" in title or "电梯" in title):
                new = ["住宅" if word == "加装" else word for word in new]
            if "格局" in old:
                term = "军售" if "F-35" in title or "出售" in title else "右倾"
                new = [term if word == "格局" else word for word in new]
            if row["platform"].strip().upper() not in topics.NEWS or not date or not start <= date <= latest:
                new = old
            if new != old:
                changes.append({"row": index + 2, "date": str(date), "title": row["title"], "before": row["keywords"], "after": ",".join(new)})
            writer.writerow({**row, "keywords": ",".join(new)})

    with (run / "changes.jsonl").open("w", encoding="utf-8") as handle:
        for change in changes:
            handle.write(json.dumps(change, ensure_ascii=False) + "\n")
    preview = Path("runtime/momentum-db.json")
    if preview.exists():
        db = json.loads(preview.read_text(encoding="utf-8"))
        by_id = {(row["platform"].strip().upper(), row["content_id"]): row for row in topics.read_csv(output)[1]}
        for row in db:
            updated = by_id.get((row["platform"].strip().upper(), row["content_id"]))
            if updated:
                row["keywords"] = updated["keywords"]
        (run / "preview-db.json").write_text(json.dumps(db, ensure_ascii=False), encoding="utf-8")

    shutil.copyfile(output, source)
    summary = {"start": str(start), "end": str(latest), "changed_rows": len(changes), "backup": str(backup), "preview": str(run / "preview-db.json")}
    (run / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
