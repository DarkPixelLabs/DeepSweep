package com.darkpixellabs.deepsweep.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Service
public class GitCloneService {
    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);
    public static final String TEMP_PREFIX = "deepsweep-";

    private final long maxGitDirectoryBytes;

    public GitCloneService(@Value("${deepsweep.max-clone-bytes:524288000}") long maxGitDirectoryBytes) {
        this.maxGitDirectoryBytes = maxGitDirectoryBytes;
    }

    public Path cloneRepository(String repoUrl, String token) throws Exception {
        Path target = Files.createTempDirectory(TEMP_PREFIX);
        try {
            var command = Git.cloneRepository().setURI(repoUrl).setDirectory(target.toFile());
            if (token != null && !token.isBlank()) {
                command.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token));
            }
            try (Git ignored = command.call()) {
                // Never log the URL, credentials, or clone output.
            }
            long gitBytes = directorySize(target.resolve(".git"));
            if (gitBytes > maxGitDirectoryBytes) {
                throw new RepoTooLargeException("Repository exceeds configured disk limit");
            }
            return target;
        } catch (Exception e) {
            deleteRecursively(target);
            throw e;
        }
    }

    public void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            log.warn("Unable to fully remove temporary workspace: {}", path.getFileName());
        }
    }

    public long directorySize(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(item -> {
                try {
                    return Files.size(item);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    throw new CleanupRuntimeException(e);
                }
            });
        } catch (CleanupRuntimeException e) {
            throw e.cause;
        }
    }

    public static class RepoTooLargeException extends IOException {
        public RepoTooLargeException(String message) {
            super(message);
        }
    }

    private static final class CleanupRuntimeException extends RuntimeException {
        private final IOException cause;
        private CleanupRuntimeException(IOException cause) {
            this.cause = cause;
        }
    }
}
