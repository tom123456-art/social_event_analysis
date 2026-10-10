package com.social.hotspot.etl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.regex.Pattern;

/** Detect irreversible damage on original text before normalization removes evidence. */
public final class TextQuality {
    private static final JsonNode RULES = loadRules();
    private static final Pattern EXPLICIT = Pattern.compile(RULES.get("explicitDamagePattern").asText());
    private static final Pattern MIXED = Pattern.compile(RULES.get("mixedDamagePattern").asText());
    private static final Pattern MISSING = Pattern.compile(RULES.get("missingCharactersPattern").asText());
    private static final int MINIMUM_FRAGMENTS = RULES.get("minimumMissingFragments").asInt();

    private TextQuality() {}

    public static String version() { return RULES.get("version").asText(); }

    public static String rejectionReason(String text) {
        if (text == null || text.isEmpty()) return null;
        if (EXPLICIT.matcher(text).find()) return "ENCODING_DAMAGE_MARKER";
        if (MIXED.matcher(text).find()) return "MIXED_ENCODING_DAMAGE";
        var matcher = MISSING.matcher(text);
        int fragments = 0;
        while (matcher.find()) {
            if (++fragments >= MINIMUM_FRAGMENTS) return "MULTIPLE_MISSING_CHARACTER_FRAGMENTS";
        }
        return null;
    }

    private static JsonNode loadRules() {
        try (InputStream input = TextQuality.class.getResourceAsStream("/text-quality-rules.json")) {
            if (input == null) throw new IllegalStateException("Missing shared text quality rules");
            return new ObjectMapper().readTree(input);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
