package com.darkpixellabs.deepsweep.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GitCloneServiceTest {
    @Test
    void cleanupRemovesEntireTemporaryWorkspace() throws Exception {
        var root = Files.createTempDirectory(GitCloneService.TEMP_PREFIX);
        Files.createDirectories(root.resolve("nested/dir"));
        Files.writeString(root.resolve("nested/dir/file.txt"), "temporary");

        new GitCloneService(524_288_000L).cleanup(root);

        assertFalse(Files.exists(root));
    }
}
