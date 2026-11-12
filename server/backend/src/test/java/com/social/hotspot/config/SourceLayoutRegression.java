package com.social.hotspot.config;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.beans.Introspector;
import java.util.HashSet;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 在不连接数据库的情况下检查组件迁移和查询参数绑定。 */
public class SourceLayoutRegression {
    public static void main(String[] args) throws Exception {
        var configuration = new Configuration();
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath:/mapper/**/*.xml");
        Set<String> expected = Set.of("OverviewMapper.xml", "HotspotTrendMapper.xml", "PlatformSpreadMapper.xml",
                "KeywordAnalysisMapper.xml", "SentimentAnalysisMapper.xml", "ContentMapper.xml", "AdminHomeMapper.xml",
                "ScreenMapper.xml", "EtlTaskMapper.xml", "UserMapper.xml");
        Set<String> actual = new HashSet<>();
        for (var resource : resources) actual.add(resource.getFilename());
        if (resources.length != expected.size() || !actual.equals(expected)) throw new AssertionError("Unexpected mapper resources: " + actual);
        for (var resource : resources) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource.toString(), configuration.getSqlFragments()).parse();
            }
        }
        for (var mapper : configuration.getMapperRegistry().getMappers()) {
            for (var method : mapper.getDeclaredMethods()) {
                if (!configuration.hasStatement(mapper.getName() + "." + method.getName())) {
                    throw new AssertionError("Missing SQL binding: " + mapper.getName() + "." + method.getName());
                }
            }
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        var names = new HashSet<String>();
        var routes = new HashSet<String>();
        for (var component : scanner.findCandidateComponents("com.social.hotspot")) {
            String className = component.getBeanClassName();
            String name = Introspector.decapitalize(className.substring(className.lastIndexOf('.') + 1));
            if (!names.add(name)) throw new AssertionError("Duplicate component: " + name);
            Class<?> type = Class.forName(className);
            RequestMapping base = type.getAnnotation(RequestMapping.class);
            if (base == null) continue;
            for (var method : type.getDeclaredMethods()) {
                GetMapping get = method.getAnnotation(GetMapping.class);
                if (get == null) continue;
                for (String prefix : base.value()) for (String suffix : get.value()) {
                    String route = prefix + suffix;
                    if (!routes.add(route)) throw new AssertionError("Duplicate GET route: " + route);
                }
            }
        }
        for (String route : Set.of("/api/analysis/overview", "/api/analysis/hotspot-trend", "/api/analysis/platform-spread",
                "/api/analysis/keyword-analysis", "/api/analysis/sentiment-analysis", "/api/analysis/content-page",
                "/api/admin/overview", "/api/analysis/screen")) {
            if (!routes.contains(route)) throw new AssertionError("Missing page route: " + route);
        }
        if (routes.contains("/api/analysis/dashboard")) throw new AssertionError("Old cross-page route remains");
        for (String page : Set.of("Overview", "HotspotTrend", "PlatformSpread", "KeywordAnalysis", "SentimentAnalysis", "Content", "AdminHome", "Screen")) {
            Class<?> service = Class.forName("com.social.hotspot.service.analysis." + page + "Service");
            Class<?> implementation = Class.forName("com.social.hotspot.service.analysis.impl." + page + "ServiceImpl");
            if (!service.isAssignableFrom(implementation)) throw new AssertionError("Wrong service implementation: " + page);
            if (!names.contains(Introspector.decapitalize(page + "ServiceImpl"))) throw new AssertionError("Implementation not scanned: " + page);
        }
        if (!names.contains("redisCache") || names.contains("analyticsCache")) throw new AssertionError("Wrong common cache component");
        String countSql = configuration.getMappedStatement("com.social.hotspot.mapper.AdminHomeMapper.eventCount").getBoundSql(null).getSql();
        if (!countSql.contains("status = 'ACTIVE'")) throw new AssertionError("Archived events must not count as active datasets");
        for (String route : Set.of("/api/front/interactions", "/api/front/submissions", "/api/admin/interactions", "/api/admin/submissions")) {
            if (routes.contains(route)) throw new AssertionError("Removed community route remains: " + route);
        }
        if (!routes.contains("/api/admin/contents") || !routes.contains("/api/front/profile")) throw new AssertionError("Content/profile routes must remain");
        System.out.println("PASS: ten page/ETL/account Mapper XMLs, active event count, removed community routes, unique components and SQL bindings.");
    }
}
