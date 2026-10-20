import argparse
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/review_csv_categories.py"
SPEC = importlib.util.spec_from_file_location("category_review", SCRIPT)
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class CategoryReviewTests(unittest.TestCase):
    def test_weibo_is_excluded_and_category_is_cleared(self):
        fields = ["content_id", "platform", "title", "content_text", "category"]
        rows = [dict(zip(fields, values)) for values in [
            ["a", " weibo ", "微博话题", "微博正文", "体育"],
            ["b", "SINA_WEIBO", "", "", "综合"],
            ["c", "NETEASE", "新闻标题", "新闻正文", "财经"],
        ]]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "in.csv"
            with source.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            original = source.read_bytes()

            def vote(batch, number):
                self.assertEqual([entry[1] for entry in batch], ["新闻标题"])
                return [(batch[0][0], number, 1, 1)]

            with patch.object(review, "votes", side_effect=vote) as model:
                review.run(argparse.Namespace(csv=source, run_dir=root / "run", max_batches=0))
                self.assertEqual(model.call_count, 2)
            with (root / "run/reviewed.csv").open(encoding="utf-8", newline="") as handle:
                output = list(csv.DictReader(handle))
            self.assertEqual([row["category"] for row in output], ["", "", "财经"])
            self.assertEqual(source.read_bytes(), original)

    def test_skips_empty_and_garbled(self):
        self.assertEqual(review.skip_reason({"title": "", "content_text": "正文"}), "empty_title_or_body")
        self.assertEqual(review.skip_reason({"title": "损坏??", "content_text": "正文"}), "garbled_text")
        self.assertEqual(review.clean("&lt;p&gt;正文&lt;/p&gt;"), "正文")
        self.assertEqual(review.clean("<script>bad</script><p>正文</p>"), "正文")

    def test_candidate_only_and_resume(self):
        fields = ["content_id", "title", "content_text", "category", "comment_count"]
        rows = [dict(zip(fields, values)) for values in [
            ["a", "百花奖名单", "电影奖项公布", "体育", "26"],
            ["b", "交叉议题", "多主题报道", "综合", "30"],
            ["c", "损坏??", "原正文", "财经", "4"],
            ["d", "", "", "综合", "1"],
        ]]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "in.csv"
            with source.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            original = source.read_bytes()
            args = argparse.Namespace(csv=source, run_dir=root / "run", max_batches=0)

            def vote(batch, number):
                return [(key, number, 5 if title == "百花奖名单" else number, 1)
                        for key, title, body in batch]

            with patch.object(review, "votes", side_effect=vote), patch.object(
                review, "third_vote", return_value=(True, {"reason": "电影奖项", "evidence": "百花奖"})
            ):
                review.run(args)
                review.run(args)
            with (root / "run/reviewed.csv").open(encoding="utf-8", newline="") as handle:
                output = list(csv.DictReader(handle))
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual((root / "run/source.snapshot.csv").read_bytes(), original)
            self.assertEqual(output[0]["category"], "文娱")
            self.assertEqual(output[1:], rows[1:])
            for before, after in zip(rows, output):
                for field in fields:
                    if field != "category":
                        self.assertEqual(before[field], after[field])


if __name__ == "__main__":
    unittest.main()
