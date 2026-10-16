# 读取规则文件并解析 JSON 配置。
import json
# 定位项目内的规则文件。
from pathlib import Path
# 使用正则表达式执行文本损坏模式匹配。
import re

# 计算规则配置文件的绝对路径。
RULES_PATH = Path(__file__).resolve().parents[5] / "resources" / "rules" / "text-quality-rules.json"
# 加载文本质量规则。
RULES = json.loads(RULES_PATH.read_text(encoding="utf-8"))
# 暴露当前规则版本。
VERSION = RULES["version"]
# 指定需要检查的文本字段。
FIELDS = ("title", "content_text", "author_name", "keywords", "category")
# 编译明确编码损坏模式。
EXPLICIT = re.compile(RULES["explicitDamagePattern"])
# 编译混合编码损坏模式。
MIXED = re.compile(RULES["mixedDamagePattern"])
# 编译疑似缺字模式。
MISSING = re.compile(RULES["missingCharactersPattern"])


def rejection_reason(text):
    # 空文本没有可判定的损坏原因。
    if not text:
        return None
    # 优先识别明确的编码替换标记。
    if EXPLICIT.search(text):
        return "ENCODING_DAMAGE_MARKER"
    # 其次识别中英文编码混杂造成的损坏。
    if MIXED.search(text):
        return "MIXED_ENCODING_DAMAGE"
    # 缺字片段达到阈值时判定为多处缺失。
    if len(MISSING.findall(text)) >= RULES["minimumMissingFragments"]:
        return "MULTIPLE_MISSING_CHARACTER_FRAGMENTS"
    return None


def record_rejection_reason(record):
    # 按字段顺序返回第一条可审计的拒绝原因。
    for field in FIELDS:
        # 检查当前字段，缺失字段按空文本处理。
        reason = rejection_reason(record.get(field, ""))
        if reason:
            return field + ":" + reason
    return None
