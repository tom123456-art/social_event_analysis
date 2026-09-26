package com.social.hotspot.etl;

import com.hankcs.hanlp.classification.classifiers.IClassifier;
import com.hankcs.hanlp.classification.classifiers.NaiveBayesClassifier;
import com.hankcs.hanlp.classification.models.NaiveBayesModel;
import com.hankcs.hanlp.corpus.io.IOUtil;
import org.apache.spark.SparkFiles;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/** Spark Executor 端的 HanLP 情感推理。 */
public final class HanlpSentimentAnalyzer {
    private static volatile ModelHolder holder;
    private static volatile String bundledModelPath;

    private HanlpSentimentAnalyzer() {
    }

    public static String analyzeEncoded(String text, String distributedModelName,
                                        double neutralThreshold, double neutralMargin) {
        if (text == null || text.isBlank()) {
            return "neutral|0.000000|1.000000|0.000000";
        }
        IClassifier classifier = classifier(distributedModelName);
        Map<String, Double> prediction = classifier.predict(text);
        double positive = probability(prediction, "positive", "pos", "\u6b63\u5411", "\u6b63\u9762", "\u79ef\u6781");
        double negative = probability(prediction, "negative", "neg", "\u8d1f\u5411", "\u8d1f\u9762", "\u6d88\u6781");
        double neutral = probability(prediction, "neutral", "neu", "\u4e2d\u6027");

        double recognized = positive + neutral + negative;
        if (recognized <= 0D) {
            throw new IllegalStateException("HanLP 模型必须包含正向、负向或中性标签："
                    + prediction.keySet());
        }
        positive /= recognized;
        neutral /= recognized;
        negative /= recognized;

        String label;
        double max = Math.max(positive, Math.max(neutral, negative));
        if (neutral >= positive && neutral >= negative) {
            label = "neutral";
        } else {
            double polarityMax = Math.max(positive, negative);
            double polarityMargin = Math.abs(positive - negative);
            // Binary positive/negative models emit neutral when confidence is insufficient.
            if (neutral == 0D && (polarityMax < neutralThreshold || polarityMargin < neutralMargin)) {
                label = "neutral";
                neutral = Math.max(0D, 1D - polarityMax);
                double total = positive + neutral + negative;
                positive /= total;
                neutral /= total;
                negative /= total;
            } else if (neutral > 0D && max < neutralThreshold) {
                label = "neutral";
            } else {
                label = positive >= negative ? "positive" : "negative";
            }
        }
        return String.format(Locale.ROOT, "%s|%.6f|%.6f|%.6f", label, positive, neutral, negative);
    }

    private static IClassifier classifier(String distributedModelName) {
        ModelHolder current = holder;
        if (current != null && current.modelName.equals(distributedModelName)) {
            return current.classifier;
        }
        synchronized (HanlpSentimentAnalyzer.class) {
            current = holder;
            if (current == null || !current.modelName.equals(distributedModelName)) {
                String resolved = resolveModel(distributedModelName);
                Object object = IOUtil.readObjectFrom(resolved);
                if (!(object instanceof NaiveBayesModel model)) {
                    throw new IllegalStateException("HanLP 朴素贝叶斯模型文件无效：" + resolved);
                }
                IClassifier classifier = new NaiveBayesClassifier(model).enableProbability(true);
                holder = current = new ModelHolder(distributedModelName, classifier);
            }
        }
        return current.classifier;
    }

    private static String resolveModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("缺少情感模型参数：--sentiment-model");
        }
        File direct = new File(modelName);
        if (direct.isFile()) {
            return direct.getAbsolutePath();
        }
        String environmentPath = System.getenv("HANLP_SENTIMENT_MODEL");
        if (environmentPath != null && !environmentPath.isBlank()) {
            File environmentFile = new File(environmentPath);
            if (environmentFile.isFile()) {
                return environmentFile.getAbsolutePath();
            }
        }
        File deploymentFile = new File("/opt/apps/social-hotspot-analytics/etl/models/weibo-sentiment.bin");
        if (deploymentFile.isFile()) {
            return deploymentFile.getAbsolutePath();
        }
        String bundledPath = bundledModel();
        if (bundledPath != null) {
            return bundledPath;
        }
        String sparkPath = SparkFiles.get(modelName);
        if (sparkPath != null && new File(sparkPath).isFile()) {
            return sparkPath;
        }
        throw new IllegalStateException("Spark Executor 未找到 HanLP 情感模型：" + modelName
                + "。请检查模型文件是否已部署到 Executor。");
    }

    private static String bundledModel() {
        String existing = bundledModelPath;
        if (existing != null) {
            return existing;
        }
        synchronized (HanlpSentimentAnalyzer.class) {
            existing = bundledModelPath;
            if (existing != null) {
                return existing;
            }
            try (InputStream input = HanlpSentimentAnalyzer.class.getResourceAsStream("/models/weibo-sentiment.bin")) {
                if (input == null) {
                    return null;
                }
                File target = File.createTempFile("weibo-sentiment-", ".bin");
                target.deleteOnExit();
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                bundledModelPath = target.getAbsolutePath();
                return bundledModelPath;
            } catch (Exception exception) {
                throw new IllegalStateException("无法解压内置 HanLP 情感模型", exception);
            }
        }
    }
    private static double probability(Map<String, Double> scores, String... aliases) {
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
                if (key.equals(alias.toLowerCase(Locale.ROOT))) {
                    return entry.getValue() == null ? 0D : Math.max(0D, entry.getValue());
                }
            }
        }
        return 0D;
    }

    private record ModelHolder(String modelName, IClassifier classifier) {
    }
}
