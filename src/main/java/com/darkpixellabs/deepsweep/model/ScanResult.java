package com.darkpixellabs.deepsweep.model;

import java.util.List;

public record ScanResult(
        String repo,
        int commitsScanned,
        List<Finding> findings,
        long durationMs
) {
}
