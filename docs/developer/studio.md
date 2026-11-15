# Sherwood Studio (git hosts, builds, vault)

Sherwood Studio is the git-hosting part of this app. It speaks to **GitHub**, **Gitea**
and **Forgejo** through one provider interface. It is host-only code under
`app/src/main/java/com/sherwood/wta/core/studio/` and is opened from the home
overflow menu (Sherwood Studio) or Settings.

Everything still ships as **one APK**. Studio is not a second app.

## What it does

| Area | Implementation | Notes |
| --- | --- | --- |
| Account | `GitHubOAuthClient` (GitHub), `GiteaApiClient` (Gitea/Forgejo) | GitHub uses OAuth **Device Flow** with a user-owned public OAuth App client ID; no client secret exists in the APK. Gitea and Forgejo are connected with a personal access token, entered in Studio → Settings next to the server URL. |
| Token storage | `GitCredentialStore` (GitHub via the existing `GitHubTokenStore` slot) | Encrypted with Android Keystore, one slot per `{provider}:{host}`. If the Keystore master key is unavailable a slot falls back to a key derived from the package name and the APK signing certificate — that is obfuscation, not secret-key encryption, so Git settings shows which of the two is in use. |
| Repositories | `GitProvider.repositories` | Public and private that the token can see. |
| Branch, merge, history | `WorkspaceService` + the provider | Branch list, checkout (clean working copy only), pull, history. Create and merge are offered only when the provider's capabilities allow them. |
| Commit / push | existing `RepoBridgeCommitUseCase` | Server-side commit with the existing review and secret scanner, through the working copy's own `GitCommitWriter`. |
| Diff | `UnifiedDiff` | Bounded local LCS; binary files report sizes only. |
| Workspace inbox | `StudioInboxTab` | Watched repositories, open pull requests, branches; per-PR details, changed files, CI check state, a review-comment box and a per-file line comment composer. Commentable line numbers are read from the diff hunk headers (`GitDiffLines.commentableNewLines`), so only new-side lines the host accepts are offered; binary/large files without a `patch` fall back to manual entry. The review and line-comment composers appear only when the driver supports them, which today means GitHub. |
| Pull requests | `GitProvider.createChangeRequest` | Open a PR from a branch (title, description, base, draft). Errors come from GitHub's own message. Review submission is `COMMENT` / `REQUEST_CHANGES` only — the app never approves or merges on its own. |
| Inspector | `ArtifactInspector` + `StudioInspectorTab` | Integrity, signature, package identity and provenance for a vault artifact, each reported separately. |
| Build Studio | `BuildStudio` | Workflow list, run status, jobs, logs, artifacts, **build this branch** (dispatch → wait → import; shown only when the provider can dispatch), and **releases** as an artifact source. |
| Artifact vault | `ArtifactVault` | APK/AAB files with SHA-256, version, source commit, release tag, run id, signature. |
| Signing Studio | `SigningStudio` + `ProjectSigningIdentity` | Signs vault APKs (apksig) and AABs with the **project's own** identity for the artifact's package. Imported keystore or the existing per-package key; a missing key must be created on purpose. |
| Export | `ArtifactExport` | Share a vault file as a `FileProvider` content URI, or copy it into a document the user picks in the system picker (`Save to Downloads`). AABs are Play upload bundles, APKs are installable. |
| Install | `ArtifactInstaller` | System installer via FileProvider with a read grant. |
| Workspace link | `WorkspaceBridgeService` | Commits `.sherwood/workspace.json`; external tools read it over raw content. No Sherwood server. |
| Agent access | `GitHubStatus` / `GitHubListRepositories` / `GitHubCreateRepository` / `GitHubImportRepository` / `GitHubOpenPullRequest` | The in-app agent reads the connection of the **active** host, creates a new independent repository, copies a repository into its own session folder, and sends edited files back as a new branch plus a pull request with a fresh approval tap. Reading and importing work on Gitea/Forgejo; the two write tools refuse there because those drivers are read-only in this build. It never forks and never merges — see [Agent access](#agent-access). |
| Endpoints | `EndpointConfig` | Every Studio address is HTTPS, validated, and overridable from Settings. |

## Provider seam

The Studio talks to a hosting platform through one interface,
`com.sherwood.wta.core.studio.git.GitProvider`. Everything a hosting platform has to
answer — account, repositories, branches, change requests, checks, commits, pipelines,
artifacts, releases, the source archive and the commit writer — is declared there with
provider-neutral models (`GitRepository`, `GitChangeRequest`, `GitPipelineRun`, …).
`BuildStudio`, `WorkspaceService`, the importer and the Studio screens call that
interface; none of them names a host.

- **Which host answers** is decided in one place, `GitProviderRegistry`. An id with no
  driver, no server URL, or a non-HTTPS URL is reported as
  `GitProviderLookup.Unavailable` with a typed reason (`NOT_IMPLEMENTED_IN_THIS_BUILD`,
  `MISSING_BASE_URL`, `NON_HTTPS_BASE_URL`) instead of falling back to GitHub — a
  fallback would send the user's edits and token to the wrong server.
- **Drivers in this build:** `GitHubProvider` (full read/write) and `GiteaProvider`
  (Gitea **and** Forgejo, read-only). The GitHub driver is a thin adapter over the
  existing `GitHubApiClient`, so no request, header or error message changed. The Gitea
  driver is `GiteaApiClient` over `{server}/api/v1`; it reads account, repositories,
  branches, pull requests, commit statuses, commits, file contents, Actions
  workflows/runs/jobs/logs/artifacts, releases and the source archive.
- **Read-only means read-only.** Every Gitea write — create repository, create branch,
  merge branch, open or merge a pull request, submit a review, post a line comment,
  dispatch a workflow — fails with `GitFailureKind.UNSUPPORTED` *before* any request is
  made, and `commitWriter()` returns null. The screens read
  `GitCapabilities` and either hide the control or replace it with one line saying why;
  they never offer a button that would always fail.
- **Capabilities are explicit.** `GitCapabilities` states what a driver can do (reviews,
  inline comments, checks, pipeline dispatch, artifacts, binary release assets, device
  flow, create/merge for branches and change requests, and whether change requests are
  called pull or merge requests). It describes the **driver's** implementation, not a
  particular server's version: a server that lacks an Actions endpoint reports a typed
  failure from that call.
- **Credentials are per provider and per server.** `GitCredentialStore` keys a token by
  `{provider}:{host}` and encrypts it with the Android Keystore master key through the
  shared `TokenCipher`; the associated data is derived from the slot, so a ciphertext
  moved to another slot fails to decrypt rather than authenticating as the wrong server.
  GitHub keeps its original `sherwood_git` slot and storage key, so an existing
  connection needs no migration. A self-hosted server must be HTTPS — the token travels
  in a header, and the URL is validated before it can be saved.
- **Provenance decides the host for a working copy.** `repo_bridge.json` records
  `provider` and `host` (schema 2). Opening, fetching, diffing or checking out a working
  copy resolves the provider from that record, not from the active setting, so a project
  imported from `git.example.com` keeps using that server after the user switches the
  Studio to GitHub. A schema-1 record (written before 2.9.7) is read as GitHub and
  upgraded on first open.
- **Import is provider-neutral.** `RepoImporter` resolves the branch, resolves the commit
  it points at, asks the provider for the source archive, extracts it through
  `RepoArchive` (one hardened extractor for every host) and writes provenance. New
  imports go to `filesDir/git_imports/`; copies written before 2.9.7 stay in
  `filesDir/github_imports/` and are still listed there.
- **Publishing goes through `GitCommitWriter`.** `RepoBridgeCommitUseCase` keeps the
  review, the secret scanner and the "new branch, never forced" rule, and asks the
  writer to build the commit and then move the ref. It resolves the writer from the
  working copy's provenance; a provider without one fails with
  `PROVIDER_READ_ONLY` instead of borrowing another host's writer.
## Agent access

The in-app agent has five git tools, registered in
`ToolRegistryFactory.baseTools()`. They still carry `GitHub*` names in this
release (the rename lands with the host that first writes through them), but
they operate on the **active** host: `GitHubStatus` and
`GitHubListRepositories` read whatever provider is configured, and
`GitHubImportRepository` imports through it. They reuse the credentials Studio
already owns — `GitCredentialStore`, filled by Settings → Git access on GitHub
or by Studio → Settings → Git hosting on a self-hosted server — so there is no
second token path and no credential inside the agent loop.

On a read-only driver (Gitea/Forgejo in this build) the two write tools refuse
with an explicit message and a `GitFailureKind.UNSUPPORTED`-shaped reason
instead of attempting a request; reading and importing keep working.

| Tool | Read-only | Effect |
| --- | --- | --- |
| `GitHubStatus` | yes | Connection state, authenticated login, the repository Studio is pointed at, and the working copies already imported. |
| `GitHubListRepositories` | yes | Repositories the token can see (name, visibility, default branch, last push). |
| `GitHubCreateRepository` | no (fresh prompt on **every** call) | GitHub only. Creates a **new, independent** repository with `POST /user/repos` (`auto_init` on, so the default branch exists immediately). Visibility defaults to private. Optional `sourceRepository` seeds it with a **public** repository's files — files only, no history and no fork link — which is the rebrand path. `.github/workflows/*` is never copied. Caps, refused rather than truncated: 500 files, 10 MB per file, 64 MB total. |
| `GitHubImportRepository` | no (permission prompt) | Copies a repository's files into the agent's own session folder, so `Read`/`Write`/`Edit`/`Glob`/`Grep` work on them. Reuses an existing Studio working copy when one matches the branch; folder `.git` is skipped, and the copy is capped at 2000 files, 32 MB total and 4 MB per file. |
| `GitHubOpenPullRequest` | no (fresh prompt on **every** call) | GitHub only in this build. Copies the edited files into a Studio working copy, creates a **new** branch from the imported base commit, commits there through `RepoBridgeCommitUseCase` (review + secret scanner), and opens a pull request. Returns the PR URL. |

`GitHubCreateRepository` (`GitHubCreateRepositoryTool.kt`) validates and
measures the source **before** the repository is created, so a refused source
never leaves a stray repository behind. It never calls `/forks` or
`/generate`: the result has no upstream link. A private source is refused when
the new repository would be public, and a failure during upload leaves the
branch at the initial commit rather than a half-applied project — every blob is
created before the tree and the commit.

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
   `PermissionChecker` never honours a remembered grant, the dialog hides
   "Always allow", and Auto-approve mode still asks for this tool.
4. **No force-push.** `updateRef` is always called with `force = false` and the
   expected base commit. A moved branch fails as a conflict.

The base branch must still point at the commit recorded at import time — the
tool refuses otherwise, so a publish can never revert commits that landed after
the import. A file over 10 MB, or a folder beyond 2000 files / 64 MB, aborts the
publish instead of shipping a partial change: a skipped file would silently be
missing from the pull request. A branch created for a push that then fails is
deleted again only while it still points at the base commit; a branch that
received the commit is left in place and the error names it.

**What the agent cannot do, by design:** merge, approve or comment on a pull
request, dispatch a workflow, delete a branch, or force-update a ref. Those
calls live in `WorkspaceService` and the provider drivers and are reached only
from Studio's own screens, where the user taps them. Creating a repository and
pushing both require a driver with a publish path; a read-only driver refuses
before the first request.

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
- `StudioBuildSourcesTest` — release and release-asset parsing (the API download
  URL is required, non-`uploaded` assets are refused), the dispatched-run
  selection rule, and the vault export name / MIME type.
- `GitProviderSeamTest` — the provider seam: repository-reference parsing (exactly
  two segments, no traversal), per-provider token formats, credential-slot derivation
  (HTTPS only, port-aware), the registry refusing an unimplemented or misconfigured
  provider instead of falling back to GitHub, and GitHub's capability declaration.

Artifact signing, keystore import and installation need a device; they are
exercised manually, not in unit tests.
