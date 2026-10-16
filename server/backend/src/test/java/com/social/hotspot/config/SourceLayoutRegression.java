package com.social.hotspot.config;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.beans.Introspector;
import java.util.HashSet;

/** Resolve relocated components and SQL bindings without a database connection. */
public class SourceLayoutRegression {
    public static void main(String[] args) throws Exception {
        var configuration = new Configuration();
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath:/mapper/**/*.xml");
        if (resources.length != 3) throw new AssertionError("Expected three mapper resources, including no old duplicates");
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
        for (var component : scanner.findCandidateComponents("com.social.hotspot")) {
            String className = component.getBeanClassName();
            String name = Introspector.decapitalize(className.substring(className.lastIndexOf('.') + 1));
            if (!names.add(name)) throw new AssertionError("Duplicate component: " + name);
        }
        System.out.println("PASS: three relocated Mapper XMLs, all SQL bindings, unique Spring component names.");
    }
}
