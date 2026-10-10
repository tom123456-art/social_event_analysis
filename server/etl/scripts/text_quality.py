import json
from pathlib import Path
import re


RULES_PATH = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "text-quality-rules.json"
RULES = json.loads(RULES_PATH.read_text(encoding="utf-8"))
VERSION = RULES["version"]
FIELDS = ("title", "content_text", "author_name", "keywords", "category")
EXPLICIT = re.compile(RULES["explicitDamagePattern"])
MIXED = re.compile(RULES["mixedDamagePattern"])
MISSING = re.compile(RULES["missingCharactersPattern"])


def rejection_reason(text):
    if not text:
        return None
    if EXPLICIT.search(text):
        return "ENCODING_DAMAGE_MARKER"
    if MIXED.search(text):
        return "MIXED_ENCODING_DAMAGE"
    if len(MISSING.findall(text)) >= RULES["minimumMissingFragments"]:
        return "MULTIPLE_MISSING_CHARACTER_FRAGMENTS"
    return None


def record_rejection_reason(record):
    for field in FIELDS:
        reason = rejection_reason(record.get(field, ""))
        if reason:
            return field + ":" + reason
    return None
