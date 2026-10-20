import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import patch_csv_keywords as patch

class SelectiveKeywordTests(unittest.TestCase):
    def row(self, title, value):
        return {'platform': 'TENCENT_NEWS', 'title': title, 'keywords': value, 'content_text': ''}

    def test_untargeted_value_is_byte_for_byte_unchanged(self):
        self.assertEqual(patch.repair(self.row('人工智能世界杯', '人工智能，世界杯'))[0], '人工智能，世界杯')

    def test_keeps_original_valuable_word(self):
        result, _ = patch.repair(self.row('机器人技术升级', '机器人,效率'))
        self.assertIn('机器人', patch.tokens(result))
        self.assertNotIn('效率', patch.tokens(result))

    def test_entity_is_deleted_without_rewriting_other_terms(self):
        self.assertEqual(patch.repair(self.row('某地新闻', '地震,省长,名古屋'))[0], '地震')

    def test_title_based_replacement(self):
        self.assertEqual(patch.repair(self.row('休假，别再羞耻', '方面,这一'))[0], '休假权益,劳动保障')

    def test_does_not_invent_missing_topic(self):
        self.assertEqual(patch.repair(self.row('95岁巴菲特，宣布重启！', '巴菲特,方面'))[0], '')

if __name__ == '__main__':
    unittest.main()
