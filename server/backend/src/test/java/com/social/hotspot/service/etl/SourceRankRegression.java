package com.social.hotspot.service.etl;
import com.social.hotspot.service.etl.impl.EtlTaskServiceImpl;

import java.util.List;

public class SourceRankRegression {
    public static void main(String[] args) throws Exception {
        var service = new EtlTaskServiceImpl(null, null, null, null);
        var method = EtlTaskServiceImpl.class.getDeclaredMethod("projectRawCells", List.class, List.class);
        method.setAccessible(true);
        var columns = EtlTaskServiceImpl.class.getDeclaredField("RAW_COLUMNS");
        columns.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> target = (List<String>) columns.get(null);
        if (target.contains("hot_rank") || target.size() != 21) throw new AssertionError("Standard schema must have 21 columns without source rank");
        var supported = EtlTaskServiceImpl.class.getDeclaredMethod("isSupportedRawSchema", List.class);
        supported.setAccessible(true);
        var crawler = EtlTaskServiceImpl.class.getDeclaredField("CRAWLER_RAW_COLUMNS");
        crawler.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> input = (List<String>) crawler.get(null);
        if (input.size() != 19 || input.contains("hot_rank") || !Boolean.TRUE.equals(supported.invoke(service, input)))
            throw new AssertionError("19-column crawler schema rejected");
        var old = new java.util.ArrayList<>(input);
        old.add(old.indexOf("keywords"), "hot_rank");
        if (Boolean.TRUE.equals(supported.invoke(service, old))) throw new AssertionError("Obsolete 20-column schema accepted");
        for (String rank : List.of("1", "999", "invalid", "")) {
            @SuppressWarnings("unchecked")
            List<String> projected = (List<String>) method.invoke(service,
                    List.of("id-001", rank, "https://example.test/item", "keyword"),
                    List.of("content_id", "hot_rank", "source_url", "keywords"));
            if (projected.size() != 21) throw new AssertionError("Source rank leaked");
            if (!"id-001".equals(projected.get(target.indexOf("content_id")))
                    || !"keyword".equals(projected.get(target.indexOf("keywords")))
                    || !"https://example.test/item".equals(projected.get(target.indexOf("source_url"))))
                throw new AssertionError("Other columns changed");
        }
        System.out.println("PASS: 19-column input, 21-column standard schema, obsolete header rejected, projection preserves remaining fields.");
    }
}
