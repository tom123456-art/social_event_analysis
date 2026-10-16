import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "server" / "python" / "src"
sys.path.insert(0, str(SRC))

from hotspot_etl.quality.text_quality import RULES_PATH


class PackageLayoutTest(unittest.TestCase):
    def test_shared_rules_and_model_metadata_exist(self):
        self.assertEqual(RULES_PATH, ROOT / "resources" / "rules" / "text-quality-rules.json")
        self.assertTrue(RULES_PATH.is_file())
        model = ROOT / "resources" / "models" / "Erlangshen-Roberta-330M-Sentiment"
        for name in ("config.json", "vocab.txt"):
            self.assertTrue((model / name).is_file(), name)

    def test_lightweight_cli_entrypoints_run_from_other_directory(self):
        for name in ("review_records.py", "audit_sentiment_gate.py"):
            result = subprocess.run(
                [sys.executable, str(SRC / "hotspot_etl" / "cli" / name), "--help"],
                cwd=ROOT / "server", capture_output=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(b"--input", result.stdout)


if __name__ == "__main__":
    unittest.main()
