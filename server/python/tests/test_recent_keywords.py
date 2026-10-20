import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import patch_recent_keywords as recent

class RecentKeywordTests(unittest.TestCase):
    def row(self, title, keywords):
        return dict(platform='TENCENT_NEWS', title=title, keywords=keywords, content_text='')

    def test_preserve_good_keywords_exactly(self):
        self.assertEqual(recent.repair(self.row('人工智能机器人技术', '人工智能，机器人')), '人工智能，机器人')

    def test_specific_airline_topic(self):
        result = recent.repair(self.row('东航通报空姐下跪道歉', '空姐,东航'))
        self.assertIn('航空服务纠纷', result)
        self.assertIn('东航', result)
        self.assertNotIn('空姐', result)

    def test_song_is_not_moon_observation(self):
        result = recent.repair(self.row('那英演唱会临时加唱弯弯的月亮', '月亮,演唱会'))
        self.assertIn('演唱会', result)
        self.assertNotIn('月亮', result)

    def test_dates(self):
        self.assertEqual(str(recent.date_of({'publish_time': '2026/9/30 12:20'})), '2026-09-30')
        self.assertIsNone(recent.date_of({'publish_time': ''}))

if __name__ == '__main__':
    unittest.main()
