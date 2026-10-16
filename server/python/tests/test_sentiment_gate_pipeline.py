"""Exercise inference CSV contracts with a stub model, without loading weights."""
import ast
import contextlib
import csv
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from hotspot_etl.quality.html_text import clean_html, POLICY as HTML_TEXT_POLICY
from hotspot_etl.quality.text_quality import FIELDS, record_rejection_reason, VERSION as TEXT_QUALITY_VERSION
from hotspot_etl.quality.sentiment_gate import eligibility, POLICY as SENTIMENT_GATE_POLICY

SOURCE = Path(__file__).resolve().parents[1] / "src" / "hotspot_etl" / "inference" / "sentiment_inference.py"


class Tokenizer:
    def encode(self, text, **options):
        return list(text[:options["max_length"]])

    def decode(self, tokens, **options):
        return "".join(tokens)

    def __len__(self):
        return 10

    def __call__(self, texts, **options):
        return {"input_ids": SimpleNamespace(to=lambda device: texts)}


class Model:
    config = SimpleNamespace(id2label={0: "negative", 1: "positive"}, vocab_size=10)

    def to(self, device):
        return self

    def eval(self):
        return self

    def __call__(self, input_ids):
        return SimpleNamespace(logits=SimpleNamespace(float=lambda: input_ids))


class GatePipelineTests(unittest.TestCase):
    def run_pipeline(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "input.csv", root / "output.csv"
            with source.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=["platform", "content_id", "title", "content_text"])
                writer.writeheader()
                writer.writerows(rows)
            args = SimpleNamespace(input=str(source), output=str(output), model=str(root), resume=False,
                                   batch_size=4, chunk_size=1)
            calls = []

            def load_model(*args, **kwargs):
                calls.append(True)
                return Model()

            scope = dict(__file__=str(SOURCE), pd=pd, Path=Path, csv=csv, hashlib=hashlib, json=json,
                         FIELDS=FIELDS, clean_html=clean_html, record_rejection_reason=record_rejection_reason,
                         TEXT_QUALITY_VERSION=TEXT_QUALITY_VERSION, HTML_TEXT_POLICY=HTML_TEXT_POLICY,
                         SENTIMENT_GATE_POLICY=SENTIMENT_GATE_POLICY, sentiment_eligibility=eligibility,
                         parse_args=lambda: args, load_tokenizer=lambda path: Tokenizer(),
                         BertForSequenceClassification=SimpleNamespace(from_pretrained=load_model),
                         SUPPORTED_PLATFORMS={"WEIBO", "TENCENT_NEWS"},
                         torch=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False),
                            device=lambda value: SimpleNamespace(type=value), inference_mode=contextlib.nullcontext,
                            softmax=lambda texts, dim: SimpleNamespace(cpu=lambda: [[.01, .99] for _ in texts])))
            tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
            nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in {"main", "model_label"}]
            exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), scope)
            with contextlib.redirect_stdout(io.StringIO()):
                scope["main"]()
            with output.open(encoding="utf-8", newline="") as handle:
                result = list(csv.DictReader(handle))
            metadata = json.loads(output.with_suffix(".csv.metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(metadata["sentiment_gate_policy"], SENTIMENT_GATE_POLICY)
            return result, len(calls)

    def test_all_skipped_does_not_load_model(self):
        rows, loads = self.run_pipeline([dict(platform="WEIBO", content_id="skip", title="", content_text="会议将在周三召开")])
        self.assertEqual(loads, 0)
        self.assertEqual(rows[0]["sentiment_status"], "SKIPPED")
        self.assertEqual(rows[0]["sentiment_positive_score"], "")

    def test_mixed_chunks_keep_skipped_and_predicted_rows(self):
        rows, loads = self.run_pipeline([
            dict(platform="WEIBO", content_id="skip", title="", content_text="会议将在周三召开"),
            dict(platform="WEIBO", content_id="predict", title="", content_text="今天真的太开心了"),
            dict(platform="TENCENT_NEWS", content_id="news", title="会议安排", content_text="会议将于周三举行，议程已经公布"),
            dict(platform="WEIBO", content_id="late", title="", content_text="甲" * 260 + "开心"),
        ])
        self.assertEqual(loads, 1)
        self.assertEqual([r["sentiment_status"] for r in rows], ["SKIPPED", "PREDICTED", "PREDICTED", "SKIPPED"])
        self.assertEqual(rows[1]["sentiment_label"], "positive")
        self.assertEqual(rows[3]["sentiment_negative_score"], "")

    def test_empty_csv_writes_header_only(self):
        rows, loads = self.run_pipeline([])
        self.assertEqual((rows, loads), ([], 0))


if __name__ == "__main__":
    unittest.main()
