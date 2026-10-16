import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from hotspot_etl.review.review_records import META, collect_low, identity, prepare, reasons, validate_correction


class ReviewRecordsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.row = dict(platform="TENCENT_NEWS", content_id="news-001", content_type="NEWS",
                        title="新闻事件标题", content_text="新闻事件的完整正文内容", author_name="作者",
                        publish_time="2026-10-12 09:00:00", crawl_time="2026-10-12 10:00:00",
                        like_count="", comment_count="5", repost_count="", favorite_count="")

    def run_prepare(self, rows, decisions=()):
        source, output, queue = (self.root / name for name in ("raw.csv", "out.csv", "queue.jsonl"))
        with source.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(self.row))
            writer.writeheader(); writer.writerows(rows)
        original = source.read_bytes()
        matched = prepare(source, output, queue, decisions)
        self.assertEqual(original, source.read_bytes())
        with output.open(encoding="utf-8", newline="") as handle:
            result = list(csv.DictReader(handle))
        return result, [json.loads(line) for line in queue.read_text(encoding="utf-8").splitlines()], matched

    def decision(self, row, status="APPROVED", correction=None, sentiment=""):
        key, version = identity(row)
        return dict(id=7, source_key=key, source_version=version, decision=status,
                    corrected_data=correction or {}, manual_sentiment=sentiment)

    def test_normal_values_and_empty_interactions_unchanged(self):
        rows, queue, matched = self.run_prepare([self.row])
        self.assertEqual({k: rows[0][k] for k in self.row}, self.row)
        self.assertEqual(rows[0]["_review_hold"], "false")
        self.assertEqual((queue, matched), ([], []))

    def test_unknown_platform_is_queued_and_held(self):
        row = dict(self.row, platform="UNKNOWN")
        rows, queue, _ = self.run_prepare([row])
        self.assertIn("UNKNOWN_PLATFORM", queue[0]["reasons"])
        self.assertEqual(rows[0]["_review_hold"], "true")

    def test_correction_is_overlay_not_source_edit(self):
        row = dict(self.row, platform="UNKNOWN")
        decision = self.decision(row, correction={"platform": "NETEASE_NEWS"}, sentiment="neutral")
        rows, queue, matched = self.run_prepare([row], [decision])
        self.assertEqual(rows[0]["platform"], "NETEASE_NEWS")
        self.assertEqual(rows[0]["_review_sentiment"], "neutral")
        self.assertEqual(rows[0]["like_count"], "")
        self.assertEqual(queue[0]["original_data"]["platform"], "UNKNOWN")
        self.assertEqual(matched, [7])

    def test_reject_removes_only_matching_version(self):
        changed = dict(self.row, content_text="来源修改后的另一版本新闻内容")
        rows, _, matched = self.run_prepare([self.row, changed], [self.decision(self.row, "REJECTED")])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["content_text"], changed["content_text"])
        self.assertEqual(matched, [7])

    def test_source_changed_decision_does_not_apply(self):
        changed = dict(self.row, comment_count="6")
        rows, _, matched = self.run_prepare([changed], [self.decision(self.row, correction={"comment_count": "99"})])
        self.assertEqual(rows[0]["comment_count"], "6")
        self.assertEqual(matched, [])

    def test_cannot_modify_immutable_id(self):
        with self.assertRaises(ValueError):
            self.run_prepare([self.row], [self.decision(self.row, correction={"content_id": "fake-id"})])

    def test_invalid_time_and_interactions_rejected(self):
        for correction in ({"crawl_time": "2026-10-11 09:00:00"}, {"comment_count": "-1"},
                           {"like_count": "1.5"}, {"like_count": "9" * 19}):
            with self.subTest(correction=correction), self.assertRaises(ValueError):
                validate_correction(dict(self.row, **correction))

    def test_encoded_html_held_but_real_html_can_clean(self):
        self.assertIn("ENCODED_HTML", reasons(dict(self.row, content_text="&lt;p&gt;新闻正文内容&lt;/p&gt;")))
        validate_correction(dict(self.row, content_text="<p>新闻正文内容</p>"))

    def test_empty_export_columns_do_not_change_version(self):
        self.assertEqual(identity(self.row), identity(dict(self.row, event_id="", url="", _review_id="7")))

    def test_low_confidence_queue_reuses_original_review(self):
        source, prediction, queue = (self.root / name for name in ("prepared.csv", "scores.csv", "low.jsonl"))
        with source.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(self.row) + list(META)); writer.writeheader()
            writer.writerow(dict(self.row, _review_id="7", _review_sentiment="", _review_hold="false"))
            writer.writerow(dict(self.row, _review_id="8", _review_sentiment="neutral", _review_hold="false"))
        with prediction.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["platform", "content_id", "sentiment_positive_score", "sentiment_negative_score"])
            writer.writeheader(); writer.writerow(dict(platform=self.row["platform"], content_id=self.row["content_id"],
                                                       sentiment_positive_score="0.6", sentiment_negative_score="0.4"))
        collect_low(source, prediction, queue, .9)
        self.assertEqual(json.loads(queue.read_text(encoding="utf-8")), {"existing_id": 7, "reasons": ["LOW_CONFIDENCE"]})


if __name__ == "__main__":
    unittest.main()
