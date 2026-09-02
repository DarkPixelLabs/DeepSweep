package com.darkpixellabs.deepsweep.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryScanServiceTest {
    @TempDir Path tempDir;

    @Test
    void findsDeletedSecretAndMarksItAbsentFromHead() throws Exception {
        Path repoDir = tempDir.resolve("fixture");
        Files.createDirectories(repoDir);
        String fakeKey = "sk-abcd1234efgh5678ijklmnop";
        PersonIdent author = new PersonIdent("Fixture Author", "fixture@example.invalid",
                ZonedDateTime.now(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

        String firstCommit;
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("secret.txt"), "key=" + fakeKey + "\n");
            git.add().addFilepattern("secret.txt").call();
            firstCommit = git.commit().setMessage("add fixture secret").setAuthor(author).setCommitter(author).call().getName();

            git.rm().addFilepattern("secret.txt").call();
            git.commit().setMessage("remove fixture secret").setAuthor(author).setCommitter(author).call();

            Files.writeString(repoDir.resolve("README.md"), "unrelated\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("add unrelated content").setAuthor(author).setCommitter(author).call();
        }

        var result = new HistoryScanService().scan(repoDir, "fixture/fixture", 3);

        assertEquals(3, result.commitsScanned());
        assertEquals(1, result.findings().size(), () -> result.findings().stream()
                .map(f -> f.secretType() + ":" + f.redactedPreview()).toList().toString());
        assertEquals(firstCommit, result.findings().getFirst().firstSeenCommit());
        assertEquals("secret.txt", result.findings().getFirst().filePath());
        assertFalse(result.findings().getFirst().stillInHead());
        assertTrue(result.findings().getFirst().redactedPreview().startsWith("sk-a"));
    }
}
