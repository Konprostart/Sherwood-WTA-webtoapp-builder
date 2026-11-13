# Local Git engine — investigation and integration point

Status: **investigated, not implemented.** This document is the output of the
Phase-3 technical spike requested in the master implementation plan. It records
what was measured, what the constraints are, which option is recommended, and the
exact seam a future implementation plugs into. Nothing in the app depends on it
yet.

## Why the app has no `.git` today

The current model is a *server-side* Git: `GitDataApiClient` +
`RepoBridgeCommitUseCase` build blobs, trees and commits through the GitHub Git
Data API, the working copy is a plain directory under
`filesDir/github_imports/<repo>_<timestamp>`, and `RepoBridgeMetadata` records the
`baseSha` that directory was imported at. That gives commit and push with
`force=false` plus an `expectedBaseSha` check, which is safe, but it has real
limits:

| Missing capability | Consequence today |
| --- | --- |
| Local object store | no offline history, no `git log` beyond the API window |
| Local branches | every switch re-downloads the repository (`WorkspaceService.checkout`) |
| Stash | `pull`/`checkout` refuse to run with local changes instead of parking them |
| 3-way merge in-app | conflicts are resolved in the GitHub UI |
| Real diff baseline | diff is computed against a single recorded `baseSha`, not a tree |

## Options considered

### 1. JGit

- **API level:** JGit 5.x/6.x/7.x requires `java.nio.file` (Android API 26).
  The last release line that works on `java.io.File` is 3.7.x (2015).
- **Licence:** EDL-1.0 (permissive) — not a blocker.
- **Size:** ~2.6 MB jar plus a transitive `slf4j` facade.
- **Verdict:** newest versions violate `minSdk` 24; the only API-24-compatible
  version is eleven years old, unmaintained, and predates SHA-256 repository
  formats, `files-backend`, commit-graph and padding-free packfiles. Shipping it
  would import unpatched CVE exposure to satisfy a compatibility claim.
- **Not recommended.**

### 2. `git` binary inside the existing Linux environment

This project already runs real Linux binaries (Node, PHP, Python, Go, WordPress)
from `LinuxEnvironmentManager`, and every downloaded binary must be launched
through `HostProcessLauncher` because `targetSdk` 36 hosts are SELinux W^X
blocked (`AGENTS.md`; issue #795). So the plumbing exists.

- **API level:** none — a static ARM64 `git` has no Android framework dependency.
- **`minSdk`:** unaffected. The environment manager already installs per-ABI.
- **Licence:** GPL-2.0. It would be **executed as a separate process only**, never
  linked, never bundled into the APK's own code. The project already ships
  GPL-licensed runtime binaries (PHP) under exactly this model, so the licence
  posture does not change. `NOTICE.md` must list it.
- **Size:** a static `git` plus its packfiles is roughly 12–20 MB per ABI; the
  binaries are downloaded on demand, not shipped in the APK.
- **Storage:** the dominant cost is the object store. This repository is ~148 MB
  by GitHub's own measure. A plain clone would be unacceptable on a phone, so a
  partial clone is mandatory:
  `git clone --filter=blob:none --no-checkout` plus sparse checkout.
- **Security:** all process launches go through `HostProcessLauncher`; arguments
  are passed as an array (no shell), the repository path is validated with the
  same canonical-path containment used by `WorkspaceService`, and credentials are
  handed to `git` through a helper rather than a command line.
- **Verdict:** **recommended**, with the caveats below.

### 3. Write a Kotlin Git implementation

Not recommended. A correct packfile/ref/delta implementation is a multi-month
project and would still lack the battle-tested conflict handling that makes local
Git worth having.

## Recommendation

Adopt the **`git` binary in the existing Linux environment** behind a
`GitEngine` interface, and keep the API engine as the always-available fallback.
The decision is deliberately *hybrid*: nothing that exists today is replaced, and
a device without the Linux environment keeps working exactly as it does now.

```
                 ┌──────────────────────────┐
                 │  WorkspaceService (UI)   │
                 └────────────┬─────────────┘
                              │
                      ┌───────▼────────┐
                      │   GitEngine    │  ← new interface
                      └───┬────────┬───┘
             ┌────────────▼──┐  ┌──▼──────────────────┐
             │ RemoteApiEngine│  │ LocalCliEngine      │
             │ (today, kept)  │  │ (git via HostProcess│
             │                │  │  Launcher, W^X safe)│
             └────────────────┘  └─────────────────────┘
```

### Interface shape (proposed)

```kotlin
interface GitEngine {
    suspend fun clone(remote: String, dir: File, depth: Int?, partial: Boolean): Result<Unit>
    suspend fun status(dir: File): Result<WorkingCopyStatus>
    suspend fun commit(dir: File, message: String, paths: List<String>): Result<String>
    suspend fun pull(dir: File): Result<PullOutcome>          // fast-forward | merge | conflict
    suspend fun push(dir: File, branch: String): Result<Unit>
    suspend fun branches(dir: File): Result<List<LocalBranch>>
    suspend fun checkout(dir: File, branch: String): Result<Unit>
    suspend fun merge(dir: File, branch: String): Result<MergeOutcome>
    suspend fun log(dir: File, limit: Int): Result<List<CommitEntry>>
    suspend fun diff(dir: File, path: String): Result<String>
}
```

`RemoteApiEngine` is a thin adapter over the **existing** `GitDataApiClient` and
`RepoBridgeCommitUseCase` — no rewrite, no behaviour change. `LocalCliEngine` is
new code that shells out through `HostProcessLauncher`.

## Integration point (where a future implementation plugs in)

1. `WorkspaceService` (already the only branch-level API the UI uses) keeps its
   public signatures. Internally it selects a backend:
   ```kotlin
   private val engine: GitEngine
       get() = if (LocalGitSupport.isAvailable(context)) local else remote
   ```
2. `LocalGitSupport.isAvailable(context)` is the single capability probe: the
   Linux environment is installed, a `git` binary is present for the ABI, and
   `HostProcessLauncher` reports an executable path. Until it returns true,
   nothing changes.
3. `RepoBridgeMetadata` gains an optional `engine: String?` field (default null =
   API engine), so existing imported projects keep their current behaviour and a
   project is never silently migrated between engines.
4. `WorkspaceException` already has typed failure kinds; a local engine adds
   `Conflict`, `Offline`, `NotInstalled` rather than new UI paths.

## Must be true before the local engine ships

- `minSdk` stays 24. The binary is a static ARM64 executable; no framework APIs
  are involved.
- Every process launch goes through `HostProcessLauncher` (never `Runtime.exec`).
- Partial clone (`--filter=blob:none`) plus a repository-size guard; refuse a
  clone that would exceed a configurable threshold rather than filling storage.
- Credentials never appear on a command line: use a `credential.helper` reading
  the token from a private file that is deleted immediately after the operation.
- `NOTICE.md` lists GPL-2.0 for the `git` binary and states the
  "invoked, never linked" position.
- A device test on API 24 and API 25 — the CI JVM tests cannot prove binary
  execution.

## What this phase deliberately does **not** do

- No dependency is added, no binary is bundled, no workflow changes.
- `WorkspaceService` is unchanged apart from the API-24 path-containment fix.
- The API engine stays the only active backend, so the shipped behaviour is exactly
  the behaviour that was verified before this document existed.
