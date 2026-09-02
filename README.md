# DeepSweep

DeepSweep is a browser-friendly Spring Boot service that scans a Git repository's **commit history** for secrets that were ever committed. It follows the default branch, examines added/modified lines from each visited commit, and reports the earliest commit where each unique file + secret value appeared.

## Why a backend?

`secretsweep` is a browser-only scanner for the current files in a repository. DeepSweep is different: reconstructing Git history and cloning repository objects requires server-side Git access and temporary disk space. DeepSweep therefore uses Java 21, Spring Boot, and JGit on the backend rather than trying to do historical Git operations in a browser.

## Features

- GitHub HTTPS repository URLs, public or private with an optional personal access token.
- JGit only: no dependency on the `git` CLI being installed.
- Default branch only for the MVP.
- Scans up to 500 commits by default; hard cap 2,000 commits.
- Scans only added/modified lines from commit diffs to avoid repeatedly scanning unchanged history.
- Deduplicates by file path + SHA-256 hash of the matched value.
- Reports first-seen commit, date, author, redacted preview, and whether the same value is still present at current HEAD.
- Temporary clones are deleted after every request and swept again during application shutdown.
- No database and no persistence of tokens or raw matches.

## Detection patterns

| Type | Pattern / rule | Confidence |
| --- | --- | --- |
| AWS Access Key | `AKIA[0-9A-Z]{16}` | high |
| Google API Key | `AIza[0-9A-Za-z\\-_]{35}` | high |
| GitHub Token | `gh[pousr]_[A-Za-z0-9]{36,}` | high |
| OpenAI-style key | `sk-[A-Za-z0-9]{20,}` | high |
| Slack Token | `xox[baprs]-[A-Za-z0-9-]{10,}` | high |
| Generic JWT | `eyJ... .eyJ... . ...` JWT-shaped value | medium |
| Private key block | PEM private-key block | high |
| Hardcoded password assignment | `pass` / `password` key assignments | high |
| High-entropy generic string | 24+ character token with Shannon entropy > ~4.0 bits/char | low |

The scanner also filters to text-like source/config/document extensions and skips common `vendor` and `generated` paths.

## Run locally

Java 21 is required.

Set an API key before starting the application:

```bash
export DEEPSWEEP_API_KEY="replace-with-a-long-random-value"
./mvnw spring-boot:run
```

Then open `http://localhost:8080`.

If the executable bit was not preserved by your checkout, run `chmod +x mvnw` once before the command above.

## Run with Docker

```bash
docker build -t deepsweep .
docker run -p 8080:8080 \
  -e DEEPSWEEP_API_KEY="replace-with-a-long-random-value" \
  deepsweep
```

The multi-stage image builds the Spring Boot JAR inside the builder image, then runs it on a Java 21 JRE image.

## API

### `GET /api/health`

The health endpoint is intentionally unauthenticated so deployment health checks can reach it.

```json
{"status":"ok"}
```

### `POST /api/scan`

`POST /api/scan` requires the configured `X-API-Key` header.

Request:

```http
X-API-Key: replace-with-your-api-key
Content-Type: application/json
```

```json
{
  "repoUrl": "https://github.com/owner/repo",
  "token": "optional-pat-for-private-repos",
  "maxCommits": 500
}
```

`token` and `maxCommits` are optional. `maxCommits` defaults to 500 and is rejected above 2,000.

The API key is configured with the `DEEPSWEEP_API_KEY` environment variable and is never committed to the repository. The default rate limit is 30 accepted scan requests per client IP per 60 seconds. It can be changed with `DEEPSWEEP_RATE_LIMIT_MAX_REQUESTS` and `DEEPSWEEP_RATE_LIMIT_WINDOW_SECONDS`.

### Authentication and rate-limit errors

- `401` — API key is missing or invalid.
- `429` — the client IP has exceeded the configured scan rate limit. A `Retry-After` header is returned.
- `503` — scan authentication has not been configured on the server; the application fails closed rather than exposing the scan endpoint.

### Response

```json
{
  "repo": "owner/repo",
  "commitsScanned": 42,
  "findings": [
    {
      "secretType": "OpenAI-style key",
      "confidence": "high",
      "filePath": "config/example.js",
      "firstSeenCommit": "0123456789abcdef...",
      "firstSeenDate": "2026-09-02T05:00:00Z",
      "firstSeenAuthor": "Example Author",
      "stillInHead": false,
      "redactedPreview": "sk-a...mnop"
    }
  ],
  "durationMs": 1234
}
```

### Other errors

Invalid repository URLs and invalid commit limits return `400`. Clone failures return `502`; a repository exceeding the configured disk limit returns `413`; unexpected scan failures return `500`.

## Safety and disk-space notes

DeepSweep handles real repository content server-side. The application:

- never logs the `token` request field, API key, or raw matched secret values;
- keeps the token request-scoped and does not persist it;
- requires an API key for `/api/scan` and rate-limits accepted scans by client IP;
- accepts only GitHub HTTPS repository URLs to avoid arbitrary clone targets;
- checks the cloned `.git` directory against `deepsweep.max-clone-bytes`, default 500 MiB;
- deletes the temporary clone in a `finally` path and attempts a startup/shutdown sweep for leftover `deepsweep-*` directories;
- limits history traversal to the requested `maxCommits` value and hard-caps it at 2,000;
- enables CORS for `/api/scan` from any origin for MVP portability. If this service is exposed publicly at scale, also restrict allowed origins and use a strong API key.

The in-memory rate limiter is intentionally simple for the MVP. In a multi-instance deployment, enforce a shared/provider-level rate limit as well so clients cannot bypass limits by switching instances.

A full clone can temporarily consume significant disk space and network bandwidth even though the scan stops after the commit-walk limit. Run the service with sufficient temporary disk space and treat scanned repository content as untrusted input.

The MVP is synchronous. The browser waits for the scan response, while the server logs commit progress. A future version can move scans to an async job model with persisted job status and websocket/polling progress.

## History behavior and limitations

DeepSweep walks the cloned repository's checked-out default branch. The selected commits are scanned oldest-to-newest after the bounded commit set is collected. For each commit, JGit compares the commit tree with its first parent and scans only added/modified content. Merge commits therefore use the first parent for the MVP.

The service does not scan other branches, Git notes, reflogs, or a general repository browser. No database is used.

## CI

`.github/workflows/build.yml` runs the Maven test suite on pushes to `main` and pull requests using Java 21.

## License

MIT. See [LICENSE](LICENSE).
