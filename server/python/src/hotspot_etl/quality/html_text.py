import re
from html.parser import HTMLParser

# 标识 HTML 清洗策略版本。
POLICY = "HTML_TEXT_V1"


class TextExtractor(HTMLParser):
    # 初始化文本片段和隐藏标签栈。
    def __init__(self):
        # 开启 HTML 字符实体解码；保存可见文本及块级标签产生的分隔空格；记录尚未闭合的脚本、样式和模板标签。
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = []

    # 处理起始标签，记录隐藏区域和块级文本边界。
    def handle_starttag(self, tag, attrs):
        # 脚本、样式和模板的正文不输出为新闻文本。
        if tag in {"script", "style", "template"}:
            # 将隐藏标签压入栈中。
            self.hidden.append(tag)
        # 在段落、列表和标题等块级内容之间插入文本分隔。
        if tag in {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "section", "article"}:
            self.parts.append(" ")

    # 处理结束标签，恢复可见文本并补齐块级分隔。
    def handle_endtag(self, tag):
        # 仅弹出与栈顶匹配的隐藏标签。
        if self.hidden and tag == self.hidden[-1]:
            # 当前隐藏区闭合后恢复外层可见性。
            self.hidden.pop()
        # 块级标签结束时也添加分隔空格。
        if tag in {"p", "div", "li", "tr", "h1", "h2", "h3", "section", "article"}:
            self.parts.append(" ")

    # 仅收集不在隐藏区域中的文本节点。
    def handle_data(self, data):
        # 隐藏标签栈为空时文本才可见。
        if not self.hidden:
            # 按 HTML 中的出现顺序保存文本。
            self.parts.append(data)


def clean_html(source):
    # 空输入原样返回，保持空值语义。
    if not source:
        return source
    # 为每段输入创建独立解析器，避免标签状态相互影响；将输入交给标准 HTML 解析器；处理解析缓冲区中尚未完成的文本。
    parser = TextExtractor()
    parser.feed(source)
    parser.close()
    # 拼接可见片段并规范化空白。
    return re.sub(r"\s+", " ", "".join(parser.parts)).strip()
