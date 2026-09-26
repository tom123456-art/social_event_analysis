package com.social.hotspot.etl;

import com.hankcs.hanlp.classification.classifiers.IClassifier;
import com.hankcs.hanlp.classification.classifiers.NaiveBayesClassifier;
import com.hankcs.hanlp.classification.corpus.MemoryDataSet;
import com.hankcs.hanlp.corpus.io.IOUtil;

import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Train a serializable HanLP Naive Bayes sentiment model from labeled text. */
public final class HanlpSentimentModelTrainer {
    private HanlpSentimentModelTrainer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);
        Path output = Path.of(required(params, "output")).toAbsolutePath().normalize();
        Charset encoding = Charset.forName(params.getOrDefault("encoding", StandardCharsets.UTF_8.name()));
        MemoryDataSet dataSet = new MemoryDataSet();

        if (params.containsKey("positive-file") || params.containsKey("negative-file")) {
            addLines(dataSet, "positive", Path.of(required(params, "positive-file")), encoding);
            addLines(dataSet, "negative", Path.of(required(params, "negative-file")), encoding);
            String neutralFile = params.get("neutral-file");
            if (neutralFile != null && !neutralFile.isBlank()) {
                addLines(dataSet, "neutral", Path.of(neutralFile), encoding);
            }
        } else {
            Path corpus = Path.of(required(params, "corpus")).toAbsolutePath().normalize();
            if (!Files.isDirectory(corpus)) {
                throw new IllegalArgumentException("Corpus directory does not exist: " + corpus);
            }
            requireCategory(corpus, "positive");
            requireCategory(corpus, "negative");
            dataSet.load(corpus.toString(), encoding.name());
        }

        if (dataSet.size() == 0) {
            throw new IllegalArgumentException("No training samples were loaded");
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        IClassifier classifier = new NaiveBayesClassifier();
        classifier.train(dataSet);
        if (!IOUtil.saveObjectTo(classifier.getModel(), output.toString())) {
            throw new IllegalStateException("HanLP could not save model: " + output);
        }
        System.out.println("HanLP sentiment model saved to " + output + ", samples=" + dataSet.size());
    }

    private static void addLines(MemoryDataSet dataSet, String label, Path file, Charset encoding) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Training file does not exist: " + file.toAbsolutePath());
        }
        long accepted = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, encoding)) {
            for (String line; (line = reader.readLine()) != null; ) {
                String text = line.trim();
                if (!text.isEmpty()) {
                    dataSet.add(label, text);
                    accepted++;
                }
            }
        }
        if (accepted == 0) {
            throw new IllegalArgumentException("Training file has no usable lines: " + file.toAbsolutePath());
        }
        System.out.println(label + " samples=" + accepted);
    }

    private static void requireCategory(Path corpus, String category) {
        if (!Files.isDirectory(corpus.resolve(category))) {
            throw new IllegalArgumentException("Missing corpus category directory: " + category + "/");
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                result.put(args[i].substring(2), args[++i]);
            }
        }
        return result;
    }

    private static String required(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }
}
