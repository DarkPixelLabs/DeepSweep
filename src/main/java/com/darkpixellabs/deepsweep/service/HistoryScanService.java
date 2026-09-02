package com.darkpixellabs.deepsweep.service;

import com.darkpixellabs.deepsweep.detection.SecretPatterns;
import com.darkpixellabs.deepsweep.model.Finding;
import com.darkpixellabs.deepsweep.model.ScanResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.FileHeader;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HistoryScanService {
    private static final Logger log = LoggerFactory.getLogger(HistoryScanService.class);

    public ScanResult scan(Path repositoryDir, String repoDisplay, int maxCommits) throws Exception {
        long started = System.currentTimeMillis();
        Map<String, TrackedFinding> findings = new LinkedHashMap<>();

        try (Repository repository = new RepositoryBuilder()
                .setGitDir(repositoryDir.resolve(".git").toFile())
                .setWorkTree(repositoryDir.toFile())
                .build();
             RevWalk walk = new RevWalk(repository)) {

            RevCommit head = walk.parseCommit(repository.resolve("HEAD"));
            List<RevCommit> commits = collectCommits(walk, head, maxCommits);
            log.info("Scanning {} commits for {}", commits.size(), repoDisplay);

            // Walk selection is newest-first; reverse it so firstSeen is evaluated in history order.
            commits.sort(Comparator.comparingInt(RevCommit::getCommitTime).thenComparing(RevCommit::getName));
            for (int index = 0; index < commits.size(); index++) {
                RevCommit commit = commits.get(index);
                scanCommit(repository, commit, findings);
                log.info("Scanned commit {}/{}", index + 1, commits.size());
            }

            markHeadPresence(repository, head, findings);
            List<Finding> result = findings.values().stream().map(TrackedFinding::toFinding).toList();
            return new ScanResult(repoDisplay, commits.size(), result, System.currentTimeMillis() - started);
        }
    }

    private List<RevCommit> collectCommits(RevWalk walk, RevCommit head, int maxCommits) throws IOException {
        walk.markStart(head);
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit : walk) {
            commits.add(commit);
            if (commits.size() >= maxCommits) {
                break;
            }
        }
        return commits;
    }

    private void scanCommit(Repository repository, RevCommit commit, Map<String, TrackedFinding> findings) throws Exception {
        RevCommit parent = commit.getParentCount() == 0 ? null : commit.getParent(0);

        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(parent == null ? null : parent.getTree(), commit.getTree());

            for (DiffEntry entry : entries) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE || !isTextPath(entry.getNewPath())) {
                    continue;
                }

                FileHeader header = formatter.toFileHeader(entry);
                List<Edit> edits = header.toEditList();
                if (edits.isEmpty()) {
                    continue;
                }

                byte[] content = loadBlob(repository, commit, entry.getNewPath());
                if (content == null || looksBinary(content)) {
                    continue;
                }
                List<String> lines = splitLines(content);
                for (Edit edit : edits) {
                    int begin = Math.max(0, edit.getBeginB());
                    int end = Math.min(lines.size(), edit.getEndB());
                    if (begin < end) {
                        scanLines(lines, begin, end, commit, entry.getNewPath(), findings);
                    }
                }
            }
        }
    }

    private void scanLines(List<String> lines, int begin, int end, RevCommit commit, String path,
                           Map<String, TrackedFinding> findings) throws Exception {
        for (int i = begin; i < end; i++) {
            for (SecretPatterns.Detection detection : SecretPatterns.detect(lines.get(i))) {
                String valueHash = sha256(detection.value());
                String key = path + ":" + valueHash;
                findings.putIfAbsent(key, new TrackedFinding(
                        detection.secretType(), detection.confidence(), path, commit.getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()).toString(), authorName(commit),
                        detection.value()
                ));
            }
        }
    }

    private void markHeadPresence(Repository repository, RevCommit head, Map<String, TrackedFinding> findings) throws Exception {
        if (findings.isEmpty()) {
            return;
        }
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(head.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (!isTextPath(path)) {
                    continue;
                }
                ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                byte[] content = loader.getBytes();
                if (looksBinary(content)) {
                    continue;
                }
                for (String line : splitLines(content)) {
                    for (SecretPatterns.Detection detection : SecretPatterns.detect(line)) {
                        String key = path + ":" + sha256(detection.value());
                        TrackedFinding tracked = findings.get(key);
                        if (tracked != null) {
                            tracked.stillInHead = true;
                        }
                    }
                }
            }
        }
    }

    private static String authorName(RevCommit commit) {
        return commit.getAuthorIdent() == null ? "unknown" : commit.getAuthorIdent().getName();
    }

    private static byte[] loadBlob(Repository repository, RevCommit commit, String path) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (treeWalk == null) {
                return null;
            }
            ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
            return loader.getBytes();
        }
    }

    private static boolean looksBinary(byte[] content) {
        int sample = Math.min(content.length, 8192);
        int control = 0;
        for (int i = 0; i < sample; i++) {
            int c = content[i] & 0xff;
            if (c == 0) {
                return true;
            }
            if (c < 9 || (c > 13 && c < 32)) {
                control++;
            }
        }
        return sample > 0 && ((double) control / sample) > 0.10;
    }

    private static List<String> splitLines(byte[] content) {
        return List.of(new String(content, StandardCharsets.UTF_8).split("\\R", -1));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private static String redact(String value) {
        if (value == null || value.length() < 8) {
            return "[redacted]";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static boolean isTextPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (name.equals("dockerfile") || name.equals(".env")) {
            return true;
        }
        String[] extensions = {
                ".js", ".ts", ".tsx", ".jsx", ".py", ".go", ".java", ".rb", ".php", ".cs",
                ".cpp", ".c", ".h", ".hpp", ".rs", ".swift", ".kt", ".kts", ".dart",
                ".sh", ".bash", ".zsh", ".fish", ".ps1", ".yml", ".yaml", ".json", ".toml",
                ".ini", ".cfg", ".conf", ".env", ".properties", ".xml", ".html", ".htm", ".md",
                ".txt", ".sql", ".graphql", ".gql"
        };
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return !name.endsWith(".lock") && !lower.contains("/vendor/") && !lower.contains("/generated/");
            }
        }
        return false;
    }

    private static final class TrackedFinding {
        private final String secretType;
        private final String confidence;
        private final String filePath;
        private final String firstSeenCommit;
        private final String firstSeenDate;
        private final String firstSeenAuthor;
        private final String value;
        private boolean stillInHead;

        private TrackedFinding(String secretType, String confidence, String filePath, String firstSeenCommit,
                               String firstSeenDate, String firstSeenAuthor, String value) {
            this.secretType = secretType;
            this.confidence = confidence;
            this.filePath = filePath;
            this.firstSeenCommit = firstSeenCommit;
            this.firstSeenDate = firstSeenDate;
            this.firstSeenAuthor = firstSeenAuthor;
            this.value = value;
        }

        private Finding toFinding() {
            return new Finding(secretType, confidence, filePath, firstSeenCommit, firstSeenDate,
                    firstSeenAuthor, stillInHead, redact(value));
        }
    }
}
