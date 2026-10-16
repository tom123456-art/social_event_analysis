from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from hotspot_etl.quality.html_text import clean_html


class HtmlTextTests(unittest.TestCase):
    def test_tags_entities_and_spacing(self):
        self.assertEqual(clean_html('<p>A&nbsp;<b>B</b></p><div>C<br>D &amp; E &#65; &#x42;</div>'),
                         'A B C D & E A B')

    def test_hidden_content_and_comments(self):
        self.assertEqual(clean_html('A<script>alert(1)</script><style>x</style><template>hidden</template><!-- note -->B'), 'AB')

    def test_plain_text_and_encoded_tags(self):
        self.assertEqual(clean_html('1 < 2 &amp; 3 > 2'), '1 < 2 & 3 > 2')
        self.assertEqual(clean_html('&lt;b&gt;text&lt;/b&gt;'), '<b>text</b>')
        self.assertIsNone(clean_html(None))
        self.assertEqual(clean_html(''), '')

    def test_inline_tags_do_not_split_words(self):
        self.assertEqual(clean_html('pre<b>fix</b>'), 'prefix')


if __name__ == '__main__':
    unittest.main()
