import importlib.util
from pathlib import Path
import unittest
import sys


ETL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ETL / "scripts"))
SPEC = importlib.util.spec_from_file_location("sentiment_inference", ETL / "scripts" / "infer_sentiment.py")
INFERENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INFERENCE)


class TokenizerIntegrationTests(unittest.TestCase):
    def test_installed_tokenizer_loads_actual_model_vocab_and_ids(self):
        model = ETL / "models" / "Erlangshen-Roberta-330M-Sentiment"
        if not (model / "vocab.txt").exists():
            self.skipTest("Local model vocabulary is not installed")
        tokenizer = INFERENCE.load_tokenizer(model)
        vocabulary = (model / "vocab.txt").read_text(encoding="utf-8").rstrip("\n").split("\n")
        self.assertEqual(len(tokenizer), 21128)
        tokens = tokenizer.tokenize("\u4eca\u5929\u5fc3\u60c5\u5f88\u597d")
        self.assertEqual(tokens, list("\u4eca\u5929\u5fc3\u60c5\u5f88\u597d"))
        for token in tokens:
            self.assertEqual(tokenizer.convert_tokens_to_ids(token), vocabulary.index(token))
        self.assertEqual(tokenizer.cls_token_id, vocabulary.index("[CLS]"))
        self.assertEqual(tokenizer.sep_token_id, vocabulary.index("[SEP]"))


if __name__ == "__main__":
    unittest.main()
