package com.social.hotspot.config;

import java.nio.file.Path;

public class ProjectPathsRegression {
    public static void main(String[] args) {
        Path root = ProjectPaths.root();
        for (String location : new String[]{"", "server", "server/backend", "server/etl"}) {
            if (!root.equals(ProjectPaths.rootFrom(root.resolve(location)))) {
                throw new AssertionError("Unexpected project root from " + location);
            }
        }
        String previous = System.getProperty("social.hotspot.home");
        try {
            System.setProperty("social.hotspot.home", root.toString());
            if (!root.equals(ProjectPaths.root())) throw new AssertionError("Explicit home override failed");
        } finally {
            if (previous == null) System.clearProperty("social.hotspot.home");
            else System.setProperty("social.hotspot.home", previous);
        }
        System.out.println("PASS: root, server, backend and ETL working directories; explicit home override.");
    }
}
