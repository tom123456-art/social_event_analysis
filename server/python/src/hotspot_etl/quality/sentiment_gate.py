"""仅判断文本是否进入情感推理，不在本模块分配情感标签。"""
import re
from hotspot_etl.quality.html_text import clean_html

# 标识当前情感门控规则版本；新闻正文要求的最小有效字符数；微博允许进入推理的态度词集合。
POLICY = "POLARITY_GATE_V2"
NEWS_MIN_CHARACTERS = 10
WEIBO_ATTITUDE = (
    "开心", "高兴", "激动", "感动", "骄傲", "支持", "赞同", "期待", "祝福", "加油",
    "喜欢", "爱了", "太棒", "厉害", "哈哈", "感谢", "难过", "痛心", "愤怒", "生气",
    "失望", "担心", "害怕", "可惜", "心疼", "无语", "恶心", "反对", "抵制", "谴责",
    "不能接受", "太惨", "太可怜", "笑死",
)
# 支持新闻正文情感推理的标准平台集合。
NEWS_PLATFORMS = {"TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER"}
# 匹配商业推广类新闻文本；匹配只包含网站导航词的新闻文本。
NEWS_ADVERTISEMENT = re.compile(r"广告推广|商业推广|扫码购买|点击购买|加群领|领取优惠券")
NEWS_NAVIGATION = re.compile(r"^(?:首页|上一页|下一页|返回顶部|网站导航|联系我们|版权声明|登录|注册|下载客户端|[\W_])+$")


def eligibility(platform, text):
    """返回是否允许推理及可审计原因，不直接生成情感标签。"""
    # 统一平台编码和输入文本类型。
    platform = str(platform or "").strip().upper()
    text = str(text or "")
    # 微博必须包含可识别的主观态度词。
    if platform == "WEIBO":
        # 删除空白，避免态度词被无意义间隔拆开。
        text = re.sub(r"\s+", "", text)
        # 逐个查找微博态度词。
        for cue in WEIBO_ATTITUDE:
            # 命中态度词后允许进入情感推理并记录命中词。
            if cue in text:
                return True, "WEIBO_ATTITUDE:" + cue
        # 没有态度词的微博不进入情感模型。
        return False, "NO_ATTITUDE_CUE"
    # 非标准新闻平台不进入新闻情感推理。
    if platform not in NEWS_PLATFORMS:
        return False, "UNSUPPORTED_PLATFORM"
    # 清理 HTML 并移除链接；压缩剩余空白字符。
    text = re.sub(r"https?://\S+", " ", clean_html(text), flags=re.I)
    text = re.sub(r"\s+", "", text)
    # 纯导航内容不具备新闻语义。
    if NEWS_NAVIGATION.fullmatch(text):
        return False, "NEWS_NAVIGATION"
    # 广告推广内容不进入新闻情感推理。
    if NEWS_ADVERTISEMENT.search(text):
        return False, "NEWS_ADVERTISEMENT"
    # 仅保留中英文和数字，计算有效文本长度。
    meaningful = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", text)
    # 有效字符不足时拒绝推理。
    if len(meaningful) < NEWS_MIN_CHARACTERS:
        return False, "NEWS_SHORT_TEXT"
    # 特殊字符占比过高时拒绝低质量文本。
    if len(meaningful) * 5 < len(text):
        # 返回新闻文本质量不足原因。
        return False, "NEWS_LOW_TEXT_QUALITY"
    # 新闻正文通过全部门控条件。
    return True, "NEWS_USABLE_TEXT"
