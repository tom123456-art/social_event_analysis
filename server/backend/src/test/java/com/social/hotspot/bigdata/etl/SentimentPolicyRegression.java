package com.social.hotspot.bigdata.etl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Standalone regression checks for incremental sentiment policy invalidation. */
public class SentimentPolicyRegression {
    public static void main(String[] args) throws Exception {
        RemoteEtlService service = new RemoteEtlService();
        var threshold = RemoteEtlService.class.getDeclaredField("sentimentMinConfidence");
        threshold.setAccessible(true);
        threshold.setDouble(service, 0.90);
        var read = RemoteEtlService.class.getDeclaredMethod("readCheckpoint", Path.class);
        read.setAccessible(true);
        Path directory = Files.createTempDirectory("sentiment-policy-test-");
        Path csv = directory.resolve("raw.csv");
        Path checkpoint = directory.resolve(".raw.csv.etl-checkpoint.properties");
        try {
            Files.writeString(csv, "platform,content_id\nWEIBO,1\n");
            check(read.invoke(service, checkpoint) == null, "missing checkpoint requests full rebuild");
            service.markFullBaseline(csv, "20261010000000000");
            check(read.invoke(service, checkpoint) != null, "matching binary policy accepts baseline");
            Properties qualityProperties = new Properties();
            try (var input = Files.newInputStream(checkpoint)) { qualityProperties.load(input); }
            qualityProperties.remove("textQualityPolicy");
            try (var output = Files.newOutputStream(checkpoint)) { qualityProperties.store(output, null); }
            check(read.invoke(service, checkpoint) == null, "changed text quality rules request global rebuild");
            service.markFullBaseline(csv, "20261010000000000");
            threshold.setDouble(service, 0.95);
            check(read.invoke(service, checkpoint) == null, "changed threshold requests full rebuild");
            threshold.setDouble(service, 0.90);
            Properties properties = new Properties();
            try (var input = Files.newInputStream(checkpoint)) { properties.load(input); }
            properties.remove("sentimentPolicy");
            try (var output = Files.newOutputStream(checkpoint)) { properties.store(output, null); }
            check(read.invoke(service, checkpoint) == null, "legacy checkpoint requests full rebuild");
            properties.setProperty("sentimentPolicy", "MODEL_BINARY_V1");
            try (var output = Files.newOutputStream(checkpoint)) { properties.store(output, null); }
            check(read.invoke(service, checkpoint) == null, "unvalidated vocabulary policy requests full rebuild");
            System.out.println("PASS: matching baseline, threshold changes, missing/legacy/unvalidated policies.");
        } finally {
            Files.deleteIfExists(checkpoint);
            Files.deleteIfExists(csv);
            Files.deleteIfExists(directory);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
