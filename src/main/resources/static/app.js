const form = document.getElementById('scan-form');
const button = document.getElementById('scan-button');
const status = document.getElementById('status');
const results = document.getElementById('results');
const summary = document.getElementById('summary');
const empty = document.getElementById('empty');
const findings = document.getElementById('findings');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  button.disabled = true;
  status.hidden = false;
  results.hidden = true;
  status.textContent = 'Cloning and scanning history — this can take a minute for larger repos.';

  const repoUrl = document.getElementById('repo-url').value.trim();
  const token = document.getElementById('token').value;
  const maxCommits = Number(document.getElementById('max-commits').value || 500);

  try {
    const response = await fetch('/api/scan', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({repoUrl, token: token || undefined, maxCommits})
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || 'Scan failed');
    render(payload);
    status.textContent = `Scan complete in ${payload.durationMs} ms.`;
  } catch (error) {
    status.textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

function render(payload) {
  results.hidden = false;
  findings.replaceChildren();
  summary.textContent = `${payload.findings.length} finding(s) · ${payload.commitsScanned} commit(s)`;
  empty.hidden = payload.findings.length !== 0;
  for (const finding of payload.findings) {
    const item = document.createElement('li');
    item.className = 'timeline-item';
    item.innerHTML = `
      <article class="finding">
        <div class="finding-file"><span class="finding-label">File</span><strong>${escapeHtml(finding.filePath)}</strong><span class="preview">${escapeHtml(finding.redactedPreview)}</span></div>
        <div class="finding-meta"><span class="finding-label">Secret type</span>${escapeHtml(finding.secretType)}</div>
        <div class="finding-meta"><span class="finding-label">Confidence</span>${escapeHtml(finding.confidence)}</div>
        <div class="finding-meta"><span class="finding-label">First seen</span><a target="_blank" rel="noreferrer" href="${commitUrl(payload.repo, finding.firstSeenCommit)}">${finding.firstSeenCommit.slice(0, 7)}</a><br>${escapeHtml(finding.firstSeenDate)}</div>
        <div class="finding-meta"><span class="finding-label">HEAD</span><span class="badge ${finding.stillInHead ? 'yes' : 'no'}">${finding.stillInHead ? 'present' : 'removed'}</span></div>
      </article>`;
    findings.appendChild(item);
  }
}

function commitUrl(repo, sha) {
  return `https://github.com/${repo}/commit/${encodeURIComponent(sha)}`;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
}
