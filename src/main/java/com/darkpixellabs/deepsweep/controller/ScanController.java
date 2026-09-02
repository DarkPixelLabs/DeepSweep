package com.darkpixellabs.deepsweep.controller;

import com.darkpixellabs.deepsweep.model.ScanRequest;
import com.darkpixellabs.deepsweep.model.ScanResult;
import com.darkpixellabs.deepsweep.service.GitCloneService;
import com.darkpixellabs.deepsweep.service.HistoryScanService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {
    private static final int DEFAULT_MAX_COMMITS = 500;
    private static final int MAX_COMMITS = 2000;
    private static final Pattern GITHUB_REPO = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    private final GitCloneService gitCloneService;
    private final HistoryScanService historyScanService;

    public ScanController(GitCloneService gitCloneService, HistoryScanService historyScanService) {
        this.gitCloneService = gitCloneService;
        this.historyScanService = historyScanService;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody ScanRequest request) {
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "repoUrl is required");
        }
        Matcher matcher = GITHUB_REPO.matcher(request.repoUrl().trim());
        if (!matcher.matches()) {
            return error(HttpStatus.BAD_REQUEST, "repoUrl must be a GitHub HTTPS repository URL");
        }

        int maxCommits = request.maxCommits() == null ? DEFAULT_MAX_COMMITS : request.maxCommits();
        if (maxCommits < 1 || maxCommits > MAX_COMMITS) {
            return error(HttpStatus.BAD_REQUEST, "maxCommits must be between 1 and 2000");
        }

        String repoDisplay = matcher.group(1) + "/" + matcher.group(2);
        Path clone = null;
        try {
            clone = gitCloneService.cloneRepository(request.repoUrl().trim(), request.token());
            ScanResult result = historyScanService.scan(clone, repoDisplay, maxCommits);
            return ResponseEntity.ok(result);
        } catch (GitCloneService.RepoTooLargeException e) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "Repository exceeds the configured disk-space limit");
        } catch (GitAPIException e) {
            return error(HttpStatus.BAD_GATEWAY, "Unable to clone repository");
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Scan failed");
        } finally {
            gitCloneService.cleanup(clone);
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
