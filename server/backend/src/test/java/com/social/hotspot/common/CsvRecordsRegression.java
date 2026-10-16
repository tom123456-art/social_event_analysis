package com.social.hotspot.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CsvRecordsRegression {
    public static void main(String[] args) throws Exception {
        String quote = Character.toString((char) 34);
        check(List.of("plain " + quote + "text", "x"), CsvRecords.cells("plain " + quote + "text,x"));
        check(List.of("a,b", "say " + quote + "hello" + quote, ""),
                CsvRecords.cells(quote + "a,b" + quote + "," + quote + "say " + quote + quote
                        + "hello" + quote + quote + quote + ","));
        String multi = "id,text\r\n1," + quote + "first\r\nsecond" + quote + "\r\n2,plain " + quote + "text\r\n";
        List<String> records = CsvRecords.records(multi);
        if (records.size() != 3) throw new AssertionError("Multiline record boundaries");
        check(List.of("1", "first\r\nsecond"), CsvRecords.cells(records.get(1)));
        try {
            CsvRecords.records("a," + quote + "not closed");
            throw new AssertionError("Unclosed quoted field accepted");
        } catch (IllegalArgumentException expected) { }
        if (args.length > 0) {
            String content = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            records = CsvRecords.records(content);
            int columns = CsvRecords.cells(records.get(0)).size();
            long quotedText = 0;
            for (int i = 1; i < records.size(); i++) {
                List<String> cells = CsvRecords.cells(records.get(i));
                if (cells.size() != columns) throw new AssertionError("Record " + (i + 1) + ": " + cells.size());
                if (cells.stream().anyMatch(c -> c.contains(quote))) quotedText++;
            }
            if ((columns == 19 || columns == 20) && records.size() > 1028) {
                List<String> row = CsvRecords.cells(records.get(1027));
                if (!row.get(3).equals("SOHU_NEWS_1004283474_130887_000180") || !row.get(7).contains(quote)) {
                    throw new AssertionError("Original record 1028 not preserved");
                }
            }
            System.out.println("PASS full CSV: records=" + (records.size() - 1) + ", columns=" + columns
                    + ", records containing literal quotes=" + quotedText);
        }
        System.out.println("PASS CSV quotes, escaped quotes, commas, multiline fields, CRLF, empty fields and unclosed fields");
    }

    private static void check(List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
