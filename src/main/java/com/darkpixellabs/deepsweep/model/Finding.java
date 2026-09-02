package com.darkpixellabs.deepsweep.model;

public record Finding(
        String secretType,
        String confidence,
        String filePath,
        String firstSeenCommit,
        String firstSeenDate,
        String firstSeenAuthor,
        boolean stillInHead,
        String redactedPreview
) {
}
