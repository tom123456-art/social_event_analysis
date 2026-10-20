import argparse
import csv
import importlib.metadata
import hashlib
import json
import inspect
import os
import sys
from pathlib import Path
from hotspot_etl.quality.text_quality import FIELDS, VERSION as TEXT_QUALITY_VERSION, record_rejection_reason
from hotspot_etl.quality.html_text import clean_html, POLICY as HTML_TEXT_POLICY
from hotspot_etl.quality.sentiment_gate import eligibility as sentiment_eligibility, POLICY as SENTIMENT_GATE_POLICY


# 本地离线接口可用，但 transformers 的版本检查不接受当前 hub 版本；仅为 huggingface-hub 返回兼容版本，其余依赖仍使用真实版本。
_metadata_version = importlib.metadata.version
importlib.metadata.version = (
    lambda name: "1.3.0" if name == "huggingface-hub" else _metadata_version(name)
)

import pandas as pd
import torch
from transformers import BertForSequenceClassification, BertTokenizer


# 只接受已定义清洗和情感策略的六个标准平台。
SUPPORTED_PLATFORMS = {
    "WEIBO", "SOHU_NEWS", "TENCENT_NEWS", "NETEASE_NEWS", "SINA_NEWS", "THE_PAPER",
}

def parse_args():
    # 配置推理命令行，指定原始或审核后输入及结果文件。
    parser = argparse.ArgumentParser(description="Offline Erlangshen sentiment inference")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    # 配置离线模型目录、推理批量和结果落盘块大小。
    parser.add_argument("--model", required=True)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--chunk-size", type=int, default=4096)
    # 仅允许与输入和策略元数据完全一致的结果续跑。
    parser.add_argument("--resume", action="store_true", help="Resume only matching binary inference output")
    # 将命令行解析为参数对象。
    return parser.parse_args()


def model_label(negative, positive):
    # 概率相同时取正面，否则取两种极性中概率较高的一种。
    return "positive" if positive >= negative else "negative"


def load_tokenizer(model_dir):
    # 定位模型配套词表；词条可能含 Unicode 行分隔符，词条 ID 只能按 LF 切分。
    vocab_path = model_dir / "vocab.txt"
    vocabulary = vocab_path.read_text(encoding="utf-8").rstrip("\n").split("\n")
    # 兼容直接接受词条映射的新分词器接口。
    if "vocab" in inspect.signature(BertTokenizer).parameters:
        # 保持文件顺序生成词条 ID，并开启英文小写处理。
        tokenizer = BertTokenizer(vocab={token: index for index, token in enumerate(vocabulary)}, do_lower_case=True)
    else:
        # 旧分词器接口直接从词表文件读取词条。
        tokenizer = BertTokenizer(vocab_file=str(vocab_path), do_lower_case=True)
    # 使用固定中文句子检查词表是否实际生效。
    probe = tokenizer.tokenize("\u4eca\u5929\u5fc3\u60c5\u5f88\u597d")
    # 词表长度不符或探测句全部未知时禁止推理。
    if len(tokenizer) != len(vocabulary) or not probe or all(token == tokenizer.unk_token for token in probe):
        raise ValueError("Tokenizer vocabulary was not loaded correctly; refusing inference")
    # 输出词表大小和探测结果，便于排查模型部署问题。
    print(f"tokenizer_vocab_size={len(tokenizer)} tokenizer_probe={probe}", flush=True)
    # 返回已验证的分词器。
    return tokenizer


def main():
    # 解析本次推理任务参数；将模型目录转换为绝对路径。
    args = parse_args()
    model_dir = Path(args.model).resolve()
    # 不存在的模型目录立即报错。
    if not model_dir.is_dir():
        raise FileNotFoundError(f"Model directory does not exist: {model_dir}")

    # 仅加载推理和审核相关字段，全部按字符串保留原始取值。
    frame = pd.read_csv(
        args.input,
        encoding="utf-8-sig",
        usecols=lambda name: name in {"platform", "content_id", "title", "content_text", "_review_hold", "_review_approved", "_review_sentiment", *FIELDS},
        dtype=str,
        keep_default_na=False,
    )
    # 平台、内容标识和正文是最小输入结构。
    required = {"platform", "content_id", "content_text"}
    # 表头缺少必要字段时输出缺失字段列表。
    if not required.issubset(frame.columns):
        missing = sorted(required.difference(frame.columns))
        raise ValueError(f"Raw CSV is missing sentiment input columns: {missing}")

    # 将平台代码去空格并转为大写；清理内容标识两侧空白。
    frame["platform"] = frame["platform"].str.strip().str.upper()
    frame["content_id"] = frame["content_id"].str.strip()
    # 没有标题列的旧输入补空列。
    if "title" not in frame.columns:
        frame["title"] = ""
    # 清理标题首尾空白；清理正文首尾空白；在清洗掉编码损坏证据之前逐条检查文本质量。
    frame["title"] = frame["title"].str.strip()
    frame["content_text"] = frame["content_text"].str.strip()
    text_damage = frame.apply(record_rejection_reason, axis=1)
    # 记录本次文本规则版本及淘汰数量；只保留未检测出编码损坏的记录。
    print(f"text_quality_policy={TEXT_QUALITY_VERSION} text_damage_discarded={text_damage.notna().sum()}", flush=True)
    frame = frame[text_damage.isna()].copy()
    # 暂缓审核记录不进入模型。
    if "_review_hold" in frame.columns:
        frame = frame[frame["_review_hold"] != "true"].copy()
    # 有人工情感标注的记录不再执行模型推理。
    if "_review_sentiment" in frame.columns:
        frame = frame[frame["_review_sentiment"] == ""].copy()
    # 稳定排序使审核通过版本在后续保留最后版本时优先胜出。
    if "_review_approved" in frame.columns:
        frame = frame.sort_values("_review_approved", kind="stable")
    # 分别移除标题和正文的 HTML 标签。
    for field in ("title", "content_text"):
        frame[field] = frame[field].map(clean_html)
    # 微博默认只用正文作为模型输入；选出需要拼接标题的非微博记录；新闻将标题和正文合并，空标题不产生前导句号。
    frame["model_text"] = frame["content_text"]
    news_mask = frame["platform"] != "WEIBO"
    frame.loc[news_mask, "model_text"] = (frame.loc[news_mask, "title"] + "。" + frame.loc[news_mask, "content_text"]).str.strip("。 ")
    # 淘汰未知平台、空 ID 和空正文，再按平台及 ID 保留最后版本。
    frame = frame[
        (frame["platform"].isin(SUPPORTED_PLATFORMS))
        & (frame["content_id"].str.len() > 0)
        & (frame["model_text"].str.len() > 0)
    ].drop_duplicates(["platform", "content_id"], keep="last").reset_index(drop=True)
    # 无候选记录时无需加载词表；门控只检查模型可见的 254 个正文 token，其余两个位置留给特殊 token。
    tokenizer = load_tokenizer(model_dir) if not frame.empty else None
    decisions = []
    # 对每条候选记录生成资格判定和可审计原因。
    for row in frame.itertuples(index=False):
        # 编码并截断后解码，保证门控窗口与模型正文窗口一致。
        window = tokenizer.decode(tokenizer.encode(row.model_text, add_special_tokens=False,
                                  truncation=True, max_length=254), skip_special_tokens=True)
        # 根据平台和可见文本执行门控。
        decisions.append(sentiment_eligibility(row.platform, window))
    # 分离是否允许推理的布尔标记；分离进入或跳过模型的具体原因。
    frame["sentiment_eligible"] = [item[0] for item in decisions]
    frame["sentiment_eligibility_reason"] = [item[1] for item in decisions]

    # 准备推理结果路径；确保输出目录存在；固定输出列顺序，与 Java 情感结果合并接口保持一致。
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    columns = [
        "platform", "content_id", "sentiment_label", "sentiment_positive_score",
        "sentiment_neutral_score", "sentiment_negative_score", "sentiment_status", "sentiment_reason",
    ]
    # 使用相邻 JSON 文件保存续跑审计元数据；按文件字节计算 SHA-256，输入任意变化都会使旧结果失去续跑资格。
    metadata_path = output_path.with_suffix(output_path.suffix + ".metadata.json")
    with open(args.input, "rb") as input_file:
        input_hash = hashlib.file_digest(input_file, "sha256").hexdigest()
    # 记录模型、文本策略、门控源码和输入内容的身份信息。
    metadata = {
        "policy": "MODEL_BINARY_V2_VALIDATED_VOCAB",
        "text_quality_policy": TEXT_QUALITY_VERSION,
        "html_text_policy": HTML_TEXT_POLICY,
        "sentiment_gate_policy": SENTIMENT_GATE_POLICY,
        "sentiment_gate_sha256": hashlib.sha256((Path(__file__).resolve().parents[1] / "quality" / "sentiment_gate.py").read_bytes()).hexdigest(),
        "input_sha256": input_hash,
        "model": str(model_dir),
        "max_length": 256,
    }
    # 已有非空输出仅在显式续跑且元数据一致时允许复用。
    if output_path.exists() and output_path.stat().st_size > 0:
        if not args.resume or not metadata_path.exists() or json.loads(metadata_path.read_text(encoding="utf-8")) != metadata:
            raise ValueError("Output already exists or resume metadata differs; use a new output file for fresh binary inference")
    # 将本次任务元数据持久化，供中断后续跑验证。
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=True, indent=2), encoding="utf-8")
    # 没有候选记录时只创建表头并结束任务。
    if frame.empty:
        pd.DataFrame(columns=columns).to_csv(output_path, index=False, encoding="utf-8")
        print("sentiment_rows=0")
        return

    # 有可用 CUDA 设备时使用 GPU，否则使用 CPU；只有实际需要推理时才初始化模型。
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = None
    # 全部记录被门控跳过时不加载大型权重。
    if frame["sentiment_eligible"].any():
        # 从本地目录加载权重，禁止联网补文件。
        model = BertForSequenceClassification.from_pretrained(model_dir, local_files_only=True)
        # 将模型配置中的标签键值标准化。
        labels = {int(key): str(value).lower() for key, value in model.config.id2label.items()}
        # 拒绝标签顺序或分类数量不符合约定的模型。
        if labels != {0: "negative", 1: "positive"}:
            raise ValueError(f"Unexpected model labels: {labels}")
        # 确保词表与模型嵌入层大小一致。
        if len(tokenizer) != model.config.vocab_size:
            raise ValueError("Model and tokenizer vocabulary sizes differ")
        # 将权重移至目标设备并关闭训练模式。
        model.to(device).eval()
    # CUDA 使用高精度矩阵乘法策略。
    if device.type == "cuda":
        torch.set_float32_matmul_precision("high")
    # CPU 批量最多八条，降低内存压力。
    batch_size = args.batch_size if device.type == "cuda" else min(args.batch_size, 8)
    print(f"policy=MODEL_BINARY_V2_VALIDATED_VOCAB sentiment_candidate_rows={int(frame['sentiment_eligible'].sum())} skipped_rows={int((~frame['sentiment_eligible']).sum())} device={device} batch_size={batch_size}", flush=True)

    # 每个记录块立即持久化，使长任务中断后可以继续处理剩余记录。
    completed = set()
    # 从已有结果中恢复已完成的内容键。
    if output_path.exists() and output_path.stat().st_size > 0:
        # 只读取身份字段，不重新加载已有概率结果；平台和内容 ID 联合作为完成标识。
        existing = pd.read_csv(output_path, usecols=["platform", "content_id"], dtype=str)
        completed = set(zip(existing["platform"], existing["content_id"]))
    # 排除已持久化的记录，并重置待处理索引。
    pending = frame[~frame.set_index(["platform", "content_id"]).index.isin(completed)].reset_index(drop=True)
    # 新结果文件先写入一次表头。
    if not output_path.exists() or output_path.stat().st_size == 0:
        pd.DataFrame(columns=columns).to_csv(output_path, index=False, encoding="utf-8")

    # 已完成记录计入整体进度。
    total_written = len(completed)
    # 将待处理记录切成可独立持久化的数据块。
    for chunk_start in range(0, len(pending), max(1, args.chunk_size)):
        # 取出当前数据块，块大小最少一条；按模型处理顺序保存当前块概率结果；只对通过门控的记录执行推理。
        chunk = pending.iloc[chunk_start:chunk_start + max(1, args.chunk_size)]
        results = []
        eligible_chunk = chunk[chunk["sentiment_eligible"]].copy()
        # 转换为分词器接受的文本列表；禁用梯度和训练状态追踪，降低推理开销。
        texts = eligible_chunk["model_text"].tolist()
        with torch.inference_mode():
            # 按设备允许的批量处理合格正文。
            for start in range(0, len(texts), batch_size):
                # 截取当前模型批量；填充并截断至 256 个 token，生成 PyTorch 张量。
                batch_texts = texts[start:start + batch_size]
                encoded = tokenizer(batch_texts, padding=True, truncation=True, max_length=256, return_tensors="pt")
                # 将所有编码张量移到模型所在设备。
                encoded = {name: value.to(device) for name, value in encoded.items()}
                # 将分类 logits 转为两类概率，并移回 CPU。
                probabilities = torch.softmax(model(**encoded).logits.float(), dim=-1).cpu()
                # 按已验证的标签顺序读取负面和正面概率。
                for probability in probabilities:
                    negative = float(probability[0])
                    positive = float(probability[1])
                    # 二分类模型不输出中性概率，中性占位固定为零。
                    results.append((model_label(negative, positive), positive, 0.0, negative))
        # 输出包含当前块的全部记录，包括被门控跳过的记录。
        output = chunk[["platform", "content_id"]].copy()
        # 未推理记录的标签和概率保持空值，不伪造中性结果。
        output["sentiment_label"] = ""
        output["sentiment_positive_score"] = ""
        output["sentiment_neutral_score"] = ""
        output["sentiment_negative_score"] = ""
        # 默认状态为跳过，原因来自前置门控。
        output["sentiment_status"] = "SKIPPED"
        output["sentiment_reason"] = chunk["sentiment_eligibility_reason"]
        # 仅对通过门控的行写入预测标签和六位小数概率。
        output.loc[eligible_chunk.index, "sentiment_label"] = [item[0] for item in results]
        output.loc[eligible_chunk.index, "sentiment_positive_score"] = [round(item[1], 6) for item in results]
        output.loc[eligible_chunk.index, "sentiment_neutral_score"] = [round(item[2], 6) for item in results]
        output.loc[eligible_chunk.index, "sentiment_negative_score"] = [round(item[3], 6) for item in results]
        # 标记实际完成模型推理的行；追加当前块结果，不重复写表头。
        output.loc[eligible_chunk.index, "sentiment_status"] = "PREDICTED"
        output.loc[eligible_chunk.index, "sentiment_reason"] = "MODEL_BINARY"
        output.to_csv(output_path, mode="a", header=False, index=False, encoding="utf-8", quoting=csv.QUOTE_MINIMAL)
        # 更新持久化记录数。
        total_written += len(output)
        print(f"sentiment_rows={total_written}/{len(frame)} device={device}", flush=True)
    # 汇总输出标签分布，供任务日志审计。
    counts = pd.read_csv(output_path)["sentiment_label"].value_counts().to_dict()
    print(f"sentiment_rows={total_written} device={device} distribution={counts}")


if __name__ == "__main__":
    # 强制 Hugging Face Hub 只使用本地资源；强制 transformers 禁止联网下载模型文件；执行命令行任务并传递退出状态。
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    sys.exit(main())
