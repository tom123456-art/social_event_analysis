// HTML 到纯文本的转换与其它清洗规则位于同一包。
package com.social.hotspot.etl.cleaning;

// 使用解析器识别标签、实体及嵌套节点，不用正则解析 HTML。
import org.jsoup.Jsoup;

// 仅处理 HTML 结构，字符规范化由 ContentNormalizer 接续执行。
public final class HtmlTextCleaner {
    // 不保存状态，只向调用方提供静态转换方法。
    private HtmlTextCleaner() {}

    // 提取来源片段的可见文本，并保留空值语义。
    public static String clean(String source) {
        // 缺失字段与空串直接返回，不进入解析器。
        if (source == null || source.isEmpty()) return source;
        // 以正文片段解析，兼容没有 html/body 外层标签的来源内容。
        var document = Jsoup.parseBodyFragment(source);
        // 连同子节点删除脚本、样式及模板，防止隐藏代码混入正文。
        document.select("script, style, template").remove();
        // 提取可见文字，将不换行空格转换为普通空格并去除首尾空白。
        return document.body().text().replace('\u00a0', ' ').strip();
    }
}
