package com.darkpixellabs.deepsweep.detection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretPatterns {
    private static final Pattern AWS = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern GOOGLE = Pattern.compile("AIza[0-9A-Za-z\\-_]{35}");
    private static final Pattern GITHUB = Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}");
    private static final Pattern OPENAI = Pattern.compile("sk-[A-Za-z0-9]{20,}");
    private static final Pattern SLACK = Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern PASSWORD = Pattern.compile(
            "(?i)(?:^|[\\s,{])(?:pass|password)\\s*(?:=|:)\\s*([\\\"']?)([^\\s,;}\\\"']{4,})\\1");
    private static final Pattern GENERIC_STRING = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z0-9+/_-]{24,}={0,2}(?![A-Za-z0-9])");

    private SecretPatterns() {
    }

    public record Detection(String secretType, String confidence, String value) {
    }

    private record Rule(String type, String confidence, Pattern pattern) {
    }

    private static final List<Rule> NAMED_RULES = List.of(
            new Rule("AWS Access Key", "high", AWS),
            new Rule("Google API Key", "high", GOOGLE),
            new Rule("GitHub Token", "high", GITHUB),
            new Rule("OpenAI-style key", "high", OPENAI),
            new Rule("Slack Token", "high", SLACK),
            new Rule("Generic JWT", "medium", JWT),
            new Rule("Private key block", "high", PRIVATE_KEY)
    );

    public static List<Detection> detect(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        Map<String, Detection> unique = new LinkedHashMap<>();
        Set<String> knownSecretValues = new HashSet<>();
        for (Rule rule : NAMED_RULES) {
            Matcher matcher = rule.pattern.matcher(content);
            while (matcher.find()) {
                String value = matcher.group();
                knownSecretValues.add(value);
                add(unique, new Detection(rule.type, rule.confidence, value));
            }
        }
        Matcher passwordMatcher = PASSWORD.matcher(content);
        while (passwordMatcher.find()) {
            String value = passwordMatcher.group(2);
            knownSecretValues.add(value);
            add(unique, new Detection("Hardcoded password assignment", "high", value));
        }
        Matcher entropyMatcher = GENERIC_STRING.matcher(content);
        while (entropyMatcher.find()) {
            String value = entropyMatcher.group();
            boolean overlapsNamedSecret = knownSecretValues.stream().anyMatch(secret -> secret.contains(value));
            if (!overlapsNamedSecret && shannonEntropy(value) > 4.0 && !looksLikeCommonText(value)) {
                add(unique, new Detection("High-entropy generic string", "low", value));
            }
        }
        return new ArrayList<>(unique.values());
    }

    static double shannonEntropy(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }
        int[] counts = new int[128];
        for (char c : value.toCharArray()) {
            if (c < counts.length) {
                counts[c]++;
            }
        }
        double entropy = 0.0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double p = (double) count / value.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private static boolean looksLikeCommonText(String value) {
        String lower = value.toLowerCase();
        return lower.matches("[a-z]+(?:-[a-z]+)+") || lower.matches("[a-z]{24,}");
    }

    private static void add(Map<String, Detection> unique, Detection detection) {
        unique.putIfAbsent(detection.secretType() + "\u0000" + detection.value(), detection);
    }
}
