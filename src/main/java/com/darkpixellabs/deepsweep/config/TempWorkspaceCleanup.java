package com.darkpixellabs.deepsweep.config;

import com.darkpixellabs.deepsweep.service.GitCloneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class TempWorkspaceCleanup {
    private static final Logger log = LoggerFactory.getLogger(TempWorkspaceCleanup.class);

    public TempWorkspaceCleanup() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupLeftovers, "deepsweep-temp-cleanup"));
    }

    private void cleanupLeftovers() {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        try (var paths = Files.list(tempRoot)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(GitCloneService.TEMP_PREFIX))
                    .forEach(path -> {
                        try {
                            GitCloneService.deleteRecursively(path);
                        } catch (Exception e) {
                            log.warn("Unable to remove leftover temporary workspace: {}", path.getFileName());
                        }
                    });
        } catch (Exception e) {
            log.warn("Unable to sweep temporary workspaces during shutdown");
        }
    }
}
