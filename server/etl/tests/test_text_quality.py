import json
from pathlib import Path
import sys
import unittest

ETL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ETL / "scripts"))
from text_quality import rejection_reason, record_rejection_reason


class TextQualityTests(unittest.TestCase):
    def test_shared_cases_reject_damage_but_preserve_normal_text(self):
        cases = json.loads((ETL / "src/test/resources/text-quality-cases.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(text=case["text"]):
                self.assertEqual(rejection_reason(case["text"]) is not None, case["reject"])

    def test_damage_in_any_display_field_rejects_the_record(self):
        for field in ("title", "content_text", "author_name", "keywords", "category"):
            self.assertEqual(record_rejection_reason({field: "\ufffd"}), field + ":ENCODING_DAMAGE_MARKER")
        self.assertIsNone(record_rejection_reason({"title": "normal", "content_text": "AI"}))


if __name__ == "__main__":
    unittest.main()
