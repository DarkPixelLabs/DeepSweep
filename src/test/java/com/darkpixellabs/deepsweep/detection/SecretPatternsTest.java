package com.darkpixellabs.deepsweep.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretPatternsTest {
    @Test void awsAccessKeyMatches() { assertType("AKIA1234567890ABCDEF", "AWS Access Key"); }
    @Test void awsAccessKeyAvoidsNearMiss() { assertNoType("AKIA1234567890ABCDE", "AWS Access Key"); }

    @Test void googleApiKeyMatches() { assertType("AIza" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R", "Google API Key"); }
    @Test void googleApiKeyAvoidsNearMiss() { assertNoType("AIza" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6", "Google API Key"); }

    @Test void githubTokenMatches() { assertType("ghp_" + "Abc1234567890Abc1234567890Abc1234567890", "GitHub Token"); }
    @Test void githubTokenAvoidsNearMiss() { assertNoType("ghp_" + "Abc1234567890Abc1234567890Abc123456789", "GitHub Token"); }

    @Test void openAiKeyMatches() { assertType("sk-" + "AbCdEf1234567890GhIjKl", "OpenAI-style key"); }
    @Test void openAiKeyAvoidsNearMiss() { assertNoType("sk-short", "OpenAI-style key"); }

    @Test void slackTokenMatches() { assertType("xoxb-1234567890-ABCDEFGHIJ", "Slack Token"); }
    @Test void slackTokenAvoidsNearMiss() { assertNoType("xoxb-short", "Slack Token"); }

    @Test void jwtMatches() { assertType("eyJabc_123.eyJdef-456.ghi_789", "Generic JWT"); }
    @Test void jwtAvoidsNearMiss() { assertNoType("eyJabc_123.not-a-jwt", "Generic JWT"); }

    @Test void privateKeyMatches() {
        assertType("-----BEGIN RSA PRIVATE KEY-----\nABC\n-----END RSA PRIVATE KEY-----", "Private key block");
    }
    @Test void privateKeyAvoidsNearMiss() {
        assertNoType("-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----", "Private key block");
    }

    @Test void passwordAssignmentMatches() { assertType("password = 's3cretValue'", "Hardcoded password assignment"); }
    @Test void passwordAssignmentAvoidsNearMiss() { assertNoType("username = 's3cretValue'", "Hardcoded password assignment"); }

    @Test void highEntropyStringMatches() {
        assertType("token=Q7x!".replace("!", "") + "9Zp2Lm8Vw4Rk6Hn3Tq5Yc1", "High-entropy generic string");
    }
    @Test void highEntropyStringAvoidsLowEntropyText() {
        assertNoType("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "High-entropy generic string");
    }

    private static void assertType(String input, String expected) {
        assertTrue(types(input).contains(expected), () -> "Expected " + expected + " in " + input);
    }

    private static void assertNoType(String input, String unexpected) {
        assertTrue(!types(input).contains(unexpected), () -> "Did not expect " + unexpected + " in " + input);
    }

    private static List<String> types(String input) {
        return SecretPatterns.detect(input).stream().map(SecretPatterns.Detection::secretType).toList();
    }
}
