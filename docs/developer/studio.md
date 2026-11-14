# Sherwood Studio (GitHub, builds, vault)

Sherwood Studio is the GitHub-facing part of this app. It is host-only code under
`app/src/main/java/com/sherwood/wta/core/studio/` and is opened from the home
overflow menu (Sherwood Studio) or Settings.

Everything still ships as **one APK**. Studio is not a second app.

## What it does

| Area | Implementation | Notes |
| --- | --- | --- |
| GitHub account | `GitHubOAuthClient` | OAuth **Device Flow** with a user-owned public OAuth App client ID. No client secret exists in the APK. |
| Token storage | existing `GitHubTokenStore` | Encrypted with Android Keystore, same store the repo bridge already uses. If the Keystore master key is unavailable the token falls back to a key derived from the package name and the APK signing certificate — that is obfuscation, not secret-key encryption, so Git settings shows which of the two is in use. |
| Repositories | `GitHubApiClient.listRepositories` | Public and private (token scope `repo`). |
| Branch, merge, history | `WorkspaceService` + `GitHubApiClient` | Branch create, checkout (clean working copy only), pull, merge, commit history. |
| Commit / push | existing `RepoBridgeCommitUseCase` | Server-side Git Data API commit with the existing review and secret scanner. |
| Diff | `UnifiedDiff` | Bounded local LCS; binary files report sizes only. |
| Workspace inbox | `StudioInboxTab` | Watched repositories, open pull requests, branches; per-PR details, changed files, CI check state, a review-comment box and a per-file line comment composer. Commentable line numbers are read from the diff hunk headers (`GitHubApiClient.commentableNewLines`), so only new-side lines GitHub accepts are offered; binary/large files without a `patch` fall back to manual entry. |
| Pull requests | `GitHubApiClient.createPullRequest` | Open a PR from a branch (title, description, base, draft). Errors come from GitHub's own message. Review submission is `COMMENT` / `REQUEST_CHANGES` only — the app never approves or merges on its own. |
| Inspector | `ArtifactInspector` + `StudioInspectorTab` | Integrity, signature, package identity and provenance for a vault artifact, each reported separately. |
| Build Studio | `BuildStudio` | Workflow list, dispatch, run status, jobs, logs, artifacts. |
| Artifact vault | `ArtifactVault` | APK/AAB files with SHA-256, version, source commit, run id, signature. |
| Signing Studio | `SigningStudio` + `ProjectSigningIdentity` | Signs vault APKs (apksig) and AABs with the **project's own** identity for the artifact's package. Imported keystore or the existing per-package key; a missing key must be created on purpose. |
| Install | `ArtifactInstaller` | System installer via FileProvider with a read grant. |
| Workspace link | `WorkspaceBridgeService` | Commits `.sherwood/workspace.json`; external tools read it over raw content. No Sherwood server. |
| Agent access | `GitHubStatus` / `GitHubListRepositories` / `GitHubImportRepository` / `GitHubOpenPullRequest` | The in-app agent can read the connection, copy a repository into its own session folder, and send the edited files back as a new branch plus a pull request with a fresh approval tap. It never merges — see [Agent access](#agent-access). |
| Endpoints | `EndpointConfig` | Every Studio address is HTTPS, validated, and overridable from Settings. |

## Signing a vault artifact

Signing is per project, never per app. `ProjectSigningIdentity` resolves the
identity for the **artifact's own package name**:

1. an imported keystore for that package (`filesDir/project_signing/`), else
2. the existing per-package key the export pipeline already uses
   (`PerAppSigningIdentity`, `filesDir/app_signing/`), else
3. **nothing** — signing is refused.

The old behaviour (signing every artifact with the host application's key) was
removed: an APK signed with Sherwood's key can never be updated by the project's
key, and an AAB signed with it permanently breaks the Play upload path.

The confirmation dialog shows the package, the identity origin (imported or
generated), the key alias, the fingerprint and the expiry date *before* signing,
and it is the only place that authorises creating a new key or signing with a
different key than the artifact already carries. Importing a keystore for a package
that already has one replaces the imported copy, and the dialog says so before the
user can confirm — otherwise one wrong import would permanently pin the package to
the wrong key. Keystore passwords are encrypted with the Android Keystore master key
and bound to the package name, and are never logged or written into a project
export.

## Inspector

`ArtifactInspector` recomputes everything from the file on disk and keeps four
claims separate: hash integrity, signature verification, package identity and
provenance. A matching SHA-256 never implies a trusted signature, and a verified
signature never implies a known origin. AABs are read from
`base/manifest/AndroidManifest.xml` (protobuf) and their JAR signature is verified
by draining every entry through `JarFile(verify = true)`.

## Local Git

The app still has no `.git`. The investigation, the rejected options and the
integration seam for a real local engine are in
[`studio-local-git.md`](studio-local-git.md). The API engine remains the only
active backend.

## Why not JGit

JGit's `java.nio.file` use requires API 26. This project keeps `minSdk` 24.
Instead of raising the floor, the app uses the GitHub Git Data API: the server
builds the tree/commit objects, and the working copy is a plain directory diffed
against the recorded base commit. Branch, merge, history and diff stay available
without a local `.git`.

## GitHub Actions artifacts

1. Studio lists workflows (`GET /actions/workflows`) and starts one with
   `workflow_dispatch`.
2. Runs, jobs and logs come from the Actions API.
3. A run's artifacts are downloaded as zips; the zip is unpacked in memory with a
   per-file cap and every `.apk`/`.aab` is imported into the vault.
4. The vault record keeps the source repository, branch, commit, workflow, run id
   and SHA-256. Verification recomputes the hash from the file and re-checks the
   APK signature with apksig.

Building itself always happens in the repository's own workflows — the app never
calls an author-owned build server.

## Workspace contract

`WorkspaceBridgeService` writes `.sherwood/workspace.json`:

```json
{
  "schema": 1,
  "project": "owner/repo",
  "branch": "main",
  "packageName": "com.example.app",
  "buildWorkflow": "release.yml",
  "appVersion": "1.0.0",
  "generatedBy": "Sherwood Studio",
  "updatedAt": "2026-01-01T00:00:00Z"
}
```

A workspace with GitHub access reads it, edits code on a branch, opens a pull
request, and the Studio's inbox shows the branch/PR for review, merge and build.
This repository itself carries one at `.sherwood/workspace.json` as a working
example.

## Agent access

The in-app agent has four GitHub tools, registered in
`ToolRegistryFactory.baseTools()`. They reuse the credential Studio already
owns — `GitHubTokenStore`, filled by Settings → Git access (personal access
token) or by the OAuth device flow — so there is no second token path and no
credential inside the agent loop.

| Tool | Read-only | Effect |
| --- | --- | --- |
| `GitHubStatus` | yes | Connection state, authenticated login, the repository Studio is pointed at, and the working copies already imported. |
| `GitHubListRepositories` | yes | Repositories the token can see (name, visibility, default branch, last push). |
| `GitHubImportRepository` | no (permission prompt) | Copies a repository's files into the agent's own session folder, so `Read`/`Write`/`Edit`/`Glob`/`Grep` work on them. Reuses an existing Studio working copy when one matches the branch; folder `.git` is skipped, and the copy is capped at 2000 files, 32 MB total and 4 MB per file. |
| `GitHubOpenPullRequest` | no (fresh prompt on **every** call) | Copies the edited files into a Studio working copy, creates a **new** branch from the imported base commit, commits there through `RepoBridgeCommitUseCase` (review + secret scanner), and opens a pull request. Returns the PR URL. |

`GitHubOpenPullRequest` (`GitHubPublishTool.kt`) enforces four rules in code,
not by prompting the model:

1. **New branch every time.** `newBranchProblem` refuses `main`/`master`, the
   imported base branch, any existing remote branch and any name outside
   `type/slug` with an allowed prefix. The working copy's `repo_bridge.json`
   is rewritten to the new branch *before* the commit plan is read, so
   `updateRef` can only advance that branch.
2. **Pull request only.** The push and `POST /pulls` happen in one invocation;
   there is no path that advances a branch without opening a PR, and no merge
   API call anywhere in the agent tools.
3. **A tap per push.** The tool returns `requiresFreshApproval() = true`, so
   `PermissionChecker` never honours a remembered grant and the dialog hides
   "Always allow".
4. **No force-push.** `updateRef` is always called with `force = false` and the
   expected base commit. A moved branch fails as a conflict.

The base branch must still point at the commit recorded at import time — the
tool refuses otherwise, so a publish can never revert commits that landed after
the import. A branch created for a push that then fails is deleted again only
while it still points at the base commit; a branch that received the commit is
left in place and the error names it.

**What the agent cannot do, by design:** merge, approve or comment on a pull
request, dispatch a workflow, delete a branch, or force-update a ref. Those
calls live in `WorkspaceService` and `GitHubApiClient` and are reached only
from Studio's own screens, where the user taps them.

## Tests

- `EndpointConfigTest` — HTTPS validation, overrides, stored differences.
- `GitHubOAuthParsingTest` — device-code and token polling parsing.
- `GitHubApiParsingTest` — repository, branch, PR, run, job, artifact parsing.
- `UnifiedDiffTest` — add/remove/replace, context, large input fallback.
- `StudioPureLogicTest` — branch names, path traversal, file-name sanitizing,
  SHA-256, manifest validation, third-party licence audit.
- `GitHubPullRequestParsingTest` — PR details, files, review comments and the
  check-state reducer (failing > pending > passing > unknown).
- `WorkspacePathSafetyTest` — canonical-path containment (`..`, prefix-similar
  siblings, symlink escape) plus a structural guard that no Studio source uses
  `java.nio.file` behind `minSdk` 24.
- `GitHubToolsTest` — agent GitHub tools: repository identifier parsing, the
  import copy guard rails (`.git`, oversized files, symlink escape) and their
  registration and read-only flags.
- `GitHubPublishTest` — the publish guard rails: branch derivation and the
  refusals for base/protected/unprefixed names, the session copy (metadata,
  `.git`, `.changes`, symlink escape), registration as a write tool, and the
  approval gate (a remembered "Always allow" never applies to a push).

Artifact signing, keystore import and installation need a device; they are
exercised manually, not in unit tests.
