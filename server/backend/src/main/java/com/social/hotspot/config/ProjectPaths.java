package com.social.hotspot.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves shared resources independently of the IDE's module working directory. */
public final class ProjectPaths {
    private ProjectPaths() {
    }

    public static Path root() {
        String configured = System.getProperty("social.hotspot.home");
        if (configured == null || configured.isBlank()) configured = System.getenv("SOCIAL_HOTSPOT_HOME");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return rootFrom(Path.of(""));
    }

    static Path rootFrom(Path directory) {
        for (Path cursor = directory.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("server/pom.xml"))
                    && Files.isRegularFile(cursor.resolve("deploy/sql/schema.sql"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("Project root not found; set SOCIAL_HOTSPOT_HOME to the system root directory.");
    }
}
