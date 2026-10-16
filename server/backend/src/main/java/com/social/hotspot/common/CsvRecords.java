package com.social.hotspot.common;

import java.util.ArrayList;
import java.util.List;

/** 只在字段起点识别引号，允许引号字段内包含逗号和换行。 */
public final class CsvRecords {
    /** 禁止实例化纯静态解析工具。 */
    private CsvRecords() {}

    /** 按引号外的换行符切分完整 CSV 记录。 */
    public static List<String> records(String content) {
        // 按原始顺序保存非空记录。
        List<String> records = new ArrayList<>();
        // 跟踪当前位置是否处于引号字段内。
        boolean quoted = false;
        // 只有字段起点的引号能够开启引号字段。
        boolean fieldStart = true;
        // 保存当前记录在原文中的起始偏移。
        int start = 0;
        // 逐字符扫描，保留引号字段内部的换行。
        for (int i = 0; i < content.length(); i++) {
            // 读取当前字符用于边界判断。
            char ch = content.charAt(i);
            if (ch == '\"' && (quoted || fieldStart)) {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '\"') i++;
                else quoted = !quoted;
                fieldStart = false;
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                // 截取换行符之前的记录，忽略纯空白行。
                String record = content.substring(start, i);
                if (!record.isBlank()) records.add(record);
                // CRLF 作为一个换行处理，跳过紧随 CR 的 LF。
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                // 下一条记录从换行符之后开始。
                start = i + 1;
                fieldStart = true;
            } else {
                // 引号外的逗号使下一字符成为字段起点。
                fieldStart = ch == ',' && !quoted;
            }
        }
        // 拒绝引号未闭合的输入，避免错误切分后续记录。
        if (quoted) throw new IllegalArgumentException("CSV quoted field is not closed");
        // 接收文件末尾没有换行符的最后一条记录。
        String last = content.substring(start);
        if (!last.isBlank()) records.add(last);
        return records;
    }

    /** 按引号外的逗号切分字段，并还原双引号转义。 */
    public static List<String> cells(String record) {
        // 保存解析完成的单元格，包括空单元格。
        List<String> cells = new ArrayList<>();
        // 累积当前单元格已经解码的字符。
        StringBuilder cell = new StringBuilder();
        // 标记当前是否在引号字段内。
        boolean quoted = false;
        // 标记当前字符能否开启引号字段。
        boolean fieldStart = true;
        // 逐字符解析字段边界和转义字符。
        for (int i = 0; i < record.length(); i++) {
            char ch = record.charAt(i);
            if (ch == '\"' && (quoted || fieldStart)) {
                if (quoted && i + 1 < record.length() && record.charAt(i + 1) == '\"') {
                    cell.append('\"');
                    i++;
                } else quoted = !quoted;
                fieldStart = false;
            } else if (ch == ',' && !quoted) {
                // 引号外的逗号结束当前字段，并清空缓冲区接收下一字段。
                cells.add(cell.toString());
                cell.setLength(0);
                fieldStart = true;
            } else {
                // 普通字符原样追加，之后的位置不再是字段起点。
                cell.append(ch);
                fieldStart = false;
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV quoted field is not closed");
        // 即使记录以逗号结尾，也保留最后一个空字段。
        cells.add(cell.toString());
        return cells;
    }
}
