package com.darkpixellabs.deepsweep.model;

public record ScanRequest(String repoUrl, String token, Integer maxCommits) {
}
