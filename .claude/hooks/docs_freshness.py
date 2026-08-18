#!/usr/bin/env python3
"""Ask, once per push, whether the documentation still tells the truth.

Runs as a Claude Code PreToolUse hook on Bash. When the command is a `git push`, it works out
which files are about to be published, maps them to the docs that claim to cover them, and — if
any of those docs are not themselves part of the push — pauses the push once with a specific
report naming the doc and the files that prompted it.

**Block once, never twice.** There is no documented loop protection for PreToolUse and no field
distinguishing a retry from a first attempt, so termination is this script's responsibility. It
writes a marker keyed on a hash of the *changed file set*:

  * decided no doc update was needed -> the retry presents an identical set, the marker hits,
    the push proceeds. One interruption.
  * edited a doc instead -> the set changed, so the key changed, but that doc is now part of the
    push and is filtered out anyway, so the report is empty and it stays silent.

Both paths terminate. Keying on the commit SHA instead would misfire on `--amend`; a
once-per-session flag would miss a second, genuinely different push.

**It fails open, always.** Not a git repo, no upstream, a git error, a malformed payload, an
unreadable doc — anything unexpected exits 0 and says nothing. A documentation reminder must
never be capable of blocking real work.

Coverage is declared by the docs themselves, as an HTML comment on the first line:

    <!-- covers: app/src/main/java/com/hillelsht/smart/data/**, tools/** -->

which keeps the mapping in the file it describes rather than in a config that can drift.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import time
from fnmatch import fnmatch
from pathlib import Path

# Machine-written content. It changes constantly, on a schedule, and never changes what the
# documentation says — asking about it would train the reader to dismiss this check.
IGNORED = (
    "packs/library/*",
    "packs/play/chains/*",
    "packs/play/vaults/*",
    "packs/durations.json",
    "app/src/main/assets/packs/*",
)

COVERS = re.compile(r"<!--\s*covers:\s*(.*?)\s*-->", re.S)
MARKER_TTL_SECONDS = 30 * 24 * 3600


def git(root, *args):
    """Run a git command, returning stdout, or None if git had anything to say about it."""
    result = subprocess.run(
        ("git", "-C", str(root)) + args,
        capture_output=True, text=True, timeout=10,
    )
    return result.stdout if result.returncode == 0 else None


def changed_paths(root):
    """Every path this push would publish.

    The union of three sets, because `git commit -m ... && git push` is a single Bash call: at
    hook time the commit may not exist yet, so staged and unstaged work has to count too.
    """
    paths = set()

    upstream = git(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    base = upstream.strip() if upstream else "origin/main"
    committed = git(root, "diff", "--name-only", f"{base}...HEAD")
    if committed is None:
        # No upstream and no origin/main to compare against — fall back to the last commit
        # alone, which is the common shape of a first push on a new branch.
        committed = git(root, "diff", "--name-only", "HEAD~1..HEAD") or ""
    paths.update(committed.split())

    paths.update((git(root, "diff", "--name-only", "--cached") or "").split())
    paths.update((git(root, "diff", "--name-only") or "").split())
    # Untracked files appear in none of the above, but `git add -A && git commit && git push`
    # publishes them — and a brand-new source file is exactly the kind of change most likely to
    # make a doc wrong.
    paths.update((git(root, "ls-files", "--others", "--exclude-standard") or "").split())

    return {p for p in paths if p and not any(fnmatch(p, pat) for pat in IGNORED)}


def coverage(root):
    """{doc path: [glob, ...]} read from each doc's own `covers:` declaration."""
    docs = sorted(root.glob("docs/*.md")) + [root / "CLAUDE.md"]
    found = {}
    for doc in docs:
        try:
            head = doc.read_text(encoding="utf-8")[:2000]
        except OSError:
            continue
        match = COVERS.search(head)
        if not match:
            continue
        globs = [g.strip() for g in match.group(1).split(",") if g.strip()]
        if globs:
            found[doc.relative_to(root).as_posix()] = globs
    return found


def match(changed, covers):
    """{doc: [file, ...]} for every doc whose globs catch something in this push."""
    hits = {}
    for doc, globs in covers.items():
        touched = sorted(
            path for path in changed
            # `**` needs both forms: fnmatch treats `*` as matching separators too, but a
            # trailing `/**` should still catch the directory's own immediate children.
            if any(fnmatch(path, g) or fnmatch(path, g.rstrip("*").rstrip("/") + "/*")
                   for g in globs)
        )
        if touched:
            hits[doc] = touched
    return hits


def prune_markers(directory):
    cutoff = time.time() - MARKER_TTL_SECONDS
    for marker in directory.glob("*"):
        try:
            if marker.stat().st_mtime < cutoff:
                marker.unlink()
        except OSError:
            pass


def report(hits):
    lines = [
        "Docs freshness check — this push is paused once, and only once.",
        "",
        "These commits touch files that documentation claims to describe:",
        "",
    ]
    for doc, files in sorted(hits.items()):
        lines.append(f"  {doc}")
        for path in files[:8]:
            lines.append(f"      <- {path}")
        if len(files) > 8:
            lines.append(f"      <- ... and {len(files) - 8} more")
    lines += [
        "",
        "None of those docs are part of this push.",
        "",
        "For each one: does this change contradict something the doc states? If so, edit the",
        "doc and include it. If the doc is still accurate, just push again — this check will",
        "not fire a second time for these same files.",
    ]
    return "\n".join(lines)


def deny(reason):
    json.dump({
        "systemMessage": "Docs freshness check paused this push — pushing again will proceed.",
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        },
    }, sys.stdout)
    sys.stdout.write("\n")


def main():
    payload = json.load(sys.stdin)
    command = payload.get("tool_input", {}).get("command", "")
    if not re.search(r"\bgit\s+push\b", command):
        return 0

    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or ".").resolve()
    if git(root, "rev-parse", "--git-dir") is None:
        return 0

    changed = changed_paths(root)
    if not changed:
        return 0

    covers = coverage(root)
    if not covers:
        return 0

    hits = match(changed, covers)
    # A doc already in the push is being updated. Nothing to ask about.
    hits = {doc: files for doc, files in hits.items() if doc not in changed}
    if not hits:
        return 0

    git_dir = Path((git(root, "rev-parse", "--absolute-git-dir") or "").strip() or root / ".git")
    markers = git_dir / "doc-check"
    markers.mkdir(parents=True, exist_ok=True)
    prune_markers(markers)

    key = hashlib.sha256("\n".join(sorted(changed)).encode()).hexdigest()[:32]
    marker = markers / key
    if marker.exists():
        return 0

    marker.write_text("")
    deny(report(hits))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # Deliberately total. This hook is a convenience; it does not get to break a push,
        # whatever went wrong.
        sys.exit(0)
