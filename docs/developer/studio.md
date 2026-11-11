# Sherwood Studio (GitHub, builds, vault)

Sherwood Studio is the GitHub-facing part of this app. It is host-only code under
`app/src/main/java/com/sherwood/wta/core/studio/` and is opened from the home
overflow menu (Sherwood Studio) or Settings.

Everything still ships as **one APK**. Studio is not a second app.

## What it does

| Area | Implementation | Notes |
| --- | --- | --- |
| GitHub account | `GitHubOAuthClient` | OAuth **Device Flow** with a user-owned public OAuth App client ID. No client secret exists in the APK. |
| Token storage | existing `GitHubTokenStore` | Encrypted with Android Keystore, same store the repo bridge already uses. |
| Repositories | `GitHubApiClient.listRepositories` | Public and private (token scope `repo`). |
| Branch, merge, history | `WorkspaceService` + `GitHubApiClient` | Branch create, checkout (clean working copy only), pull, merge, commit history. |
| Commit / push | existing `RepoBridgeCommitUseCase` | Server-side Git Data API commit with the existing review and secret scanner. |
| Diff | `UnifiedDiff` | Bounded local LCS; binary files report sizes only. |
| Workspace inbox | `StudioInboxTab` | Watched repositories, open pull requests, branches; merge and open on GitHub. |
| Build Studio | `BuildStudio` | Workflow list, dispatch, run status, jobs, logs, artifacts. |
| Artifact vault | `ArtifactVault` | APK/AAB files with SHA-256, version, source commit, run id, signature. |
| Signing Studio | `SigningStudio` | Signs vault APKs (apksig) and AABs with the app's current identity; signed copies are new vault records. |
| Install | `ArtifactInstaller` | System installer via FileProvider with a read grant. |
| Workspace link | `WorkspaceBridgeService` | Commits `.sherwood/workspace.json`; external tools read it over raw content. No Sherwood server. |
| Endpoints | `EndpointConfig` | Every Studio address is HTTPS, validated, and overridable from Settings. |

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

## Tests

- `EndpointConfigTest` — HTTPS validation, overrides, stored differences.
- `GitHubOAuthParsingTest` — device-code and token polling parsing.
- `GitHubApiParsingTest` — repository, branch, PR, run, job, artifact parsing.
- `UnifiedDiffTest` — add/remove/replace, context, large input fallback.
- `StudioPureLogicTest` — branch names, path traversal, file-name sanitizing,
  SHA-256, manifest validation, third-party licence audit.

Artifact signing and installation need a device; they are exercised manually, not
in unit tests.
