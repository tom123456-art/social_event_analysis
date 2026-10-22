package com.social.hotspot.service.ai;

import com.social.hotspot.exception.BusinessException;
import java.util.*;
import java.util.regex.*;

/** Deliberately small SELECT grammar: no joins, subqueries, arbitrary functions or expressions. */
public final class AiSqlPolicy {
    private AiSqlPolicy() { }
    public static String validate(String sql, Map<String, Set<String>> schema) {
        if (sql == null || sql.length() > 4000 || sql.contains(";") || sql.contains("--") || sql.contains("/*")
                || sql.contains("#") || sql.contains("\\") || sql.contains("`")) reject();
        String identifier = "[a-z_][a-z0-9_]*";
        String expression = "(?:" + identifier + "|(?:count|sum|avg|min|max)\\(\\s*(?:\\*|" + identifier + ")\\s*\\))";
        String column = expression + "(?:\\s+as\\s+" + identifier + ")?";
        String literal = "(?:'[^'\\r\\n]*'|-?[0-9]+(?:\\.[0-9]+)?)";
        String predicate = identifier + "\\s*(?:=|!=|<>|>=|<=|>|<|\\blike\\b)\\s*" + literal;
        Pattern select = Pattern.compile("^select\\s+(" + column + "(?:\\s*,\\s*" + column + ")*)\\s+from\\s+(" + identifier + ")"
                + "(?:\\s+where\\s+" + predicate + "(?:\\s+and\\s+" + predicate + ")*)?"
                + "(?:\\s+group\\s+by\\s+" + identifier + "(?:\\s*,\\s*" + identifier + ")*)?"
                + "(?:\\s+order\\s+by\\s+(?:" + identifier + ")(?:\\s+(?:asc|desc))?(?:\\s*,\\s*" + identifier + "(?:\\s+(?:asc|desc))?)*)?"
                + "(?:\\s+limit\\s+([0-9]+))?\\s*$", Pattern.CASE_INSENSITIVE);
        Matcher match = select.matcher(sql.trim());
        if (!match.matches()) reject();
        String table = match.group(2).toLowerCase(Locale.ROOT);
        Set<String> columns = schema.get(table);
        if (columns == null) reject();
        Matcher selectedColumns = Pattern.compile("(?:count|sum|avg|min|max)\\(\\s*(\\*|" + identifier + ")\\s*\\)|(" + identifier + ")", Pattern.CASE_INSENSITIVE)
                .matcher(match.group(1).replaceAll("(?i)\\s+as\\s+" + identifier, ""));
        while (selectedColumns.find()) {
            String field = selectedColumns.group(1) != null ? selectedColumns.group(1) : selectedColumns.group(2);
            if (!field.equals("*") && !columns.contains(field.toLowerCase(Locale.ROOT))) reject();
        }
        Set<String> allowed = new HashSet<>(columns);
        allowed.add(table);
        allowed.addAll(Set.of("select", "from", "where", "and", "as", "group", "by", "order", "asc", "desc", "limit", "like", "count", "sum", "avg", "min", "max"));
        String withoutStrings = sql.toLowerCase(Locale.ROOT).replaceAll("'[^']*'", "''");
        Matcher predicates = Pattern.compile("(" + identifier + ")\\s*(?:=|!=|<>|>=|<=|>|<|\\blike\\b)").matcher(withoutStrings);
        while (predicates.find()) if (!columns.contains(predicates.group(1))) reject();
        Matcher groups = Pattern.compile("\\bgroup\\s+by\\s+(.+?)(?:\\border\\b|\\blimit\\b|$)").matcher(withoutStrings);
        if (groups.find()) for (String field : groups.group(1).split(",")) if (!columns.contains(field.trim())) reject();
        Matcher aliases = Pattern.compile("\\bas\\s+(" + identifier + ")").matcher(withoutStrings);
        while (aliases.find()) allowed.add(aliases.group(1));
        Matcher words = Pattern.compile(identifier).matcher(withoutStrings);
        while (words.find()) if (!allowed.contains(words.group())) reject();
        if (match.group(3) != null) {
            try { if (Integer.parseInt(match.group(3)) < 1 || Integer.parseInt(match.group(3)) > 100) reject(); }
            catch (NumberFormatException ex) { reject(); }
        }
        return sql.trim() + (match.group(3) == null ? " LIMIT 100" : "");
    }
    private static void reject() { throw new BusinessException("只允许白名单分析表的单条简单查询，最多一百行；不允许联表、子查询、注释或写入操作。"); }
}
