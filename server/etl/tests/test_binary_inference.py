import ast
from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "scripts" / "infer_sentiment.py"
TREE = ast.parse(SOURCE.read_text(encoding="utf-8"))
SCOPE = {}
FUNCTION = next(node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name == "model_label")
exec(compile(ast.Module(body=[FUNCTION], type_ignores=[]), str(SOURCE), "exec"), SCOPE)


class BinaryInferenceTests(unittest.TestCase):
    def test_label_is_model_argmax_without_keyword_overrides(self):
        self.assertEqual(SCOPE["model_label"](.99, .01), "negative")
        self.assertEqual(SCOPE["model_label"](.01, .99), "positive")
        self.assertEqual(SCOPE["model_label"](.50, .50), "positive")
        self.assertEqual(SCOPE["model_label"](.51, .49), "negative")

    def test_no_neutral_or_keyword_policy_remains(self):
        source = SOURCE.read_text(encoding="utf-8")
        for legacy in ("NEUTRAL_MARKERS", "POSITIVE_MARKERS", "NEGATIVE_MARKERS", "final_label", "neutral-confidence"):
            self.assertNotIn(legacy, source)
        self.assertIn("positive, 0.0, negative", source)

    def test_resume_requires_policy_and_input_metadata(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('"policy": "MODEL_BINARY_V2_VALIDATED_VOCAB"', source)
        self.assertIn('"input_sha256": input_hash', source)
        self.assertIn("not args.resume", source)
        self.assertIn('labels != {0: "negative", 1: "positive"}', source)

    def test_tokenizer_validates_vocab_and_known_chinese_tokens(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('"vocab" in inspect.signature(BertTokenizer).parameters', source)
        self.assertIn('len(tokenizer) != len(vocabulary)', source)
        self.assertIn('all(token == tokenizer.unk_token for token in probe)', source)
        self.assertIn('len(tokenizer) != model.config.vocab_size', source)


if __name__ == "__main__":
    unittest.main()
