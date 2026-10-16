import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from hotspot_etl.quality.sentiment_gate import eligibility
from hotspot_etl.review.review_records import collect_low


class SentimentGateTests(unittest.TestCase):
    def test_news_event_polarity_without_reporter_emotion(self):
        self.assertTrue(eligibility("TENCENT_NEWS", "此次事故造成三人死亡，相关部门正在调查。")[0])
        self.assertTrue(eligibility("SOHU_NEWS", "被困人员全部获救，救援行动结束。")[0])
        self.assertTrue(eligibility("NETEASE_NEWS", "企业收入同比增长百分之十。")[0])

    def test_objective_and_negated_facts(self):
        self.assertTrue(eligibility("SINA_NEWS", "会议将于周三举行，议程已经公布。")[0])
        self.assertTrue(eligibility("THE_PAPER", "无人员死亡，相关部门正在核查。")[0])
        self.assertTrue(eligibility("THE_PAPER", "有关部门公布死亡率计算方法。")[0])
        self.assertTrue(eligibility("THE_PAPER", "报告显示当地死亡率显著下降。")[0])
        self.assertTrue(eligibility("THE_PAPER", "价格上涨情况将在下周发布。")[0])

    def test_news_quality_boundaries(self):
        self.assertTrue(eligibility("THE_PAPER", "https://example.com 会议将于周三举行议程已经公布")[0])
        for platform in ("TENCENT_NEWS", "SOHU_NEWS", "NETEASE_NEWS", "SINA_NEWS", "THE_PAPER"):
            self.assertTrue(eligibility(platform, "甲" * 10)[0])
            self.assertFalse(eligibility(platform, "甲" * 9)[0])
        for text in ("https://example.com/news", "<script>abcdefghijk</script>",
                     "首页上一页下一页返回顶部", "扫码购买最新商品领取优惠券", "甲" * 10 + "!" * 50):
            self.assertFalse(eligibility("THE_PAPER", text)[0], text)

    def test_weibo_requires_expressed_attitude(self):
        self.assertFalse(eligibility("WEIBO", "此次事故造成三人死亡，相关部门正在调查。")[0])
        self.assertTrue(eligibility("WEIBO", "看到这条消息太痛心了，希望查清原因。")[0])
        self.assertTrue(eligibility("WEIBO", "我一点也不开心。")[0])
        self.assertFalse(eligibility("UNKNOWN", "开心")[0])

    def test_skipped_results_do_not_enter_low_confidence_queue(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, prediction, queue = (root / name for name in ("input.csv", "prediction.csv", "queue.jsonl"))
            source.write_text("platform,content_id,content_text\nWEIBO,abc,今天发布会议议程\n", encoding="utf-8")
            prediction.write_text("platform,content_id,sentiment_status,sentiment_positive_score,sentiment_negative_score\nWEIBO,abc,SKIPPED,,\n", encoding="utf-8")
            collect_low(source, prediction, queue, .95)
            self.assertEqual(queue.read_text(encoding="utf-8"), "")


if __name__ == "__main__":
    unittest.main()
