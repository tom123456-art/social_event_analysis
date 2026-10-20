import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/clean_csv_keywords.py"
SPEC = importlib.util.spec_from_file_location("keyword_cleanup", SCRIPT)
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class KeywordCleanupTests(unittest.TestCase):
    def row(self, title, **extra):
        return {"platform": "TENCENT_NEWS", "title": title, "content_text": "",
                "keywords": "习近平,北京,广东省,记者,美国,群众,效率", **extra}

    def test_weibo_and_unknown_platforms_are_empty(self):
        for platform in ["WEIBO", "weibo", "Weibo", "WEIBO ", "SINA_WEIBO", "", "undefined"]:
            self.assertEqual(review.keywords(self.row("世界杯决赛", platform=platform))[0], [])

    def test_people_places_roles_countries_are_not_keywords(self):
        terms, _ = review.keywords(self.row("习近平在北京会晤美国总统 广东省记者报道"))
        self.assertIn("国际关系", terms)
        self.assertFalse(set(terms) & {"习近平", "北京", "广东省", "记者", "美国", "总统"})

    def test_generates_topics_not_template_topics(self):
        row = self.row("西藏吉隆口岸泥石流航拍画面", content_text="外交渠道的沟通仍在继续。经济和能源因素与地缘政治交织，使局势更加复杂。")
        self.assertEqual(review.keywords(row)[0], ["泥石流", "地质灾害", "灾害防治"])
        self.assertIn("健康管理", review.keywords(self.row("70岁吕良伟分享冻龄秘诀"))[0])

    def test_does_not_guess_from_category(self):
        self.assertEqual(review.keywords(self.row("夯实基础开新局", category="政治"))[0], [])
        self.assertEqual(review.keywords(self.row("???"))[0], [])

    def test_no_sports_or_ev_hallucination(self):
        self.assertNotIn("射击运动", review.keywords(self.row("持手枪抢劫"))[0])
        self.assertNotIn("新能源汽车", review.keywords(self.row("燃油车降价"))[0])

    def test_apply_preserves_other_columns_and_backup(self):
        fields = ["platform", "title", "content_text", "keywords", "content_id", "like_count"]
        rows = [{**self.row("世界杯决赛", content_text="正文\n包含逗号,和引号" + chr(34)), "content_id": "a", "like_count": "001"},
                {**self.row("人工智能", platform="WEIBO"), "content_id": "b", "like_count": ""}]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "news.csv"
            with source.open("w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            original = source.read_bytes()
            review.process(source, root / "run", apply=True)
            self.assertEqual((root / "run" / root.name / "original.csv").read_bytes(), original)
            actual_fields, actual = review.read_csv(source)
            self.assertEqual(actual_fields, fields)
            for before, after in zip(rows, actual):
                self.assertEqual({k: v for k, v in before.items() if k != "keywords"},
                                 {k: v for k, v in after.items() if k != "keywords"})
            self.assertEqual(actual[1]["keywords"], "")


if __name__ == "__main__":
    unittest.main()
