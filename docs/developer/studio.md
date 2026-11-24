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
| Repositories | `GitProvider.repositories` / `.createRepository` | Public and private that the token can see; create a new, independent one (never a fork) on any driver that supports it. |
| Branch, merge, history | `WorkspaceService` + the provider | Branch list, checkout (clean working copy only), pull, history. Create, merge and change-request merging are offered only when the provider's capabilities allow them; Gitea has no branch-merge endpoint, so that control is hidden there. |
| Commit / push | existing `RepoBridgeCommitUseCase` | Server-side commit with the existing review and secret scanner, through the working copy's own `GitCommitWriter`. |
| Diff | `UnifiedDiff` | Bounded local LCS; binary files report sizes only. |
| Workspace inbox | `StudioInboxTab` | Watched repositories, open pull requests, branches; per-PR details, changed files, CI check state, a review-comment box and a per-file line comment composer. Commentable line numbers are read from the diff hunk headers (`GitDiffLines.commentableNewLines`), so only new-side lines the host accepts are offered; binary/large files without a `patch` fall back to manual entry. Reviews appear where the driver supports them (GitHub and Gitea/Forgejo); per-line comments stay GitHub-only because Gitea needs the pull request's diff positions, which this client does not build. |
| Pull requests | `GitProvider.createChangeRequest` | Open a PR from a branch (title, description, base, draft). Drafts are offered only where the create endpoint has a draft field (GitHub); Gitea's does not, so its dialog hides the toggle instead of ignoring it. Errors come from the host's own message. Reviews are `APPROVED` / `COMMENT` / `REQUEST_CHANGES`; the app never merges on its own. |
| Inspector | `ArtifactInspector` + `StudioInspectorTab` | Integrity, signature, package identity and provenance for a vault artifact, each reported separately. |
| Build Studio | `BuildStudio` | Workflow list, run status, jobs, logs, artifacts, **build this branch** (dispatch → wait → import; shown only when the provider can dispatch), and **releases** as an artifact source. |
| Artifact vault | `ArtifactVault` | APK/AAB files with SHA-256, version, source commit, release tag, run id, signature. |
| Signing Studio | `SigningStudio` + `ProjectSigningIdentity` | Signs vault APKs (apksig) and AABs with the **project's own** identity for the artifact's package. Imported keystore or the existing per-package key; a missing key must be created on purpose. |
| Export | `ArtifactExport` | Share a vault file as a `FileProvider` content URI, or copy it into a document the user picks in the system picker (`Save to Downloads`). AABs are Play upload bundles, APKs are installable. |
| Install | `ArtifactInstaller` | System installer via FileProvider with a read grant. |
| Workspace link | `WorkspaceBridgeService` | Commits `.sherwood/workspace.json`; external tools read it over raw content. No Sherwood server. |
| Agent access | `GitStatus` / `GitListRepositories` / `GitCreateRepository` / `GitImportRepository` / `GitOpenChangeRequest` | The in-app agent reads the connection of the **active** host, creates a new independent repository (optionally seeded from a public one), copies a repository into its own session folder, and sends edited files back as a new branch plus a pull request with a fresh approval tap. Both write paths work on GitHub and on Gitea/Forgejo. It never forks and never merges — see [Agent access](#agent-access). |
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
- **Drivers in this build:** `GitHubProvider` and `GiteaProvider` (Gitea **and**
  Forgejo), both read/write. The GitHub driver is a thin adapter over the existing
  `GitHubApiClient`, so no request, header or error message changed. The Gitea driver is
  `GiteaApiClient` over `{server}/api/v1`: account, repositories, branches, pull
  requests, commit statuses, commits, file contents, git trees, Actions
  workflows/runs/jobs/logs/artifacts, releases and the source archive — plus create a
  repository, create/delete a branch, open/merge/review a pull request, dispatch a
  workflow, and commit a change set.
- **Capabilities are explicit.** `GitCapabilities` states what a driver can do (reviews,
  inline comments, checks, pipeline dispatch, artifacts, binary release assets, device
  flow, draft change requests, create/merge for branches and change requests, and whether
  change requests are called pull or merge requests). A control whose capability is false
  is hidden or replaced by one line saying why; a control is never offered if it would
  always fail. What has no endpoint stays off: Gitea has no branch merge and no draft
  field on creation, so `mergeBranches` and `draftChangeRequests` are false there.
- **Two publish shapes, one rule.** `GitCommitWriter` has a staged shape (build the commit
  object, then move the ref — GitHub) and a single-step shape (`commitsInOneStep`, where
  the host writes the commit and the branch together — Gitea/Forgejo). The publisher
  checks the shape; a single-step driver reports `UNSUPPORTED` for `createCommit` /
  `moveBranch` rather than faking them. Neither shape ever forces a ref or `force_push`es.
- **Per-file preconditions.** A `GitFileChange` carries the blob sha it was diffed
  against. Gitea's `change-files` endpoint validates it and refuses the whole commit when
  a file changed remotely, so a concurrent edit is a conflict rather than an overwrite.
  GitHub's tree update is layered on the imported base and the ref move is non-forced.
- **Publish limits are per writer.** `publishLimits` bounds one commit (files, bytes,
  per-file size) for the host's request body and the phone's memory. Exceeding a limit
  refuses the publish; a change set is never truncated, because a partial push would look
  complete.
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
  review, the secret scanner and the "new branch, never forced" rule. It resolves the
  writer from the working copy's provenance; a provider without one fails with
  `PROVIDER_READ_ONLY` instead of borrowing another host's writer. Its
  `commitNewBranch` is the single entry point the agent uses: the branch name is checked
  again (never the base branch, never `main`/`master`), and the working copy's provenance
  is updated to the new branch only after the host accepted the commit.

## Agent access

The in-app agent has five git tools, registered in
`ToolRegistryFactory.baseTools()`. They operate on the **active** host: `GitStatus` and
`GitListRepositories` read whatever provider is configured, `GitImportRepository` imports
through it, and `GitCreateRepository` / `GitOpenChangeRequest` write through it. They
reuse the credentials Studio already owns — `GitCredentialStore`, filled by Settings → Git
access on GitHub or by Studio → Settings → Git hosting on a self-hosted server — so there
is no second token path and no credential inside the agent loop.

| Tool | Read-only | Effect |
| --- | --- | --- |
| `GitStatus` | yes | Connection state, authenticated login, the repository Studio is pointed at, the working copies already imported, and which writes the host can serve. |
| `GitListRepositories` | yes | Repositories the token can see (name, visibility, default branch, last push). |
| `GitCreateRepository` | no (fresh prompt on **every** call) | Creates a **new, independent** repository with the host's create endpoint (`auto_init` on, so the default branch exists immediately). Visibility defaults to private. Optional `sourceRepository` seeds it with a **public** repository's files on the same host — files only, no history and no fork link — which is the rebrand path. No CI definition is copied (`.github/workflows/*`, `.gitea/workflows/*`, `.forgejo/workflows/*`, `.gitlab-ci.yml`). Caps, refused rather than truncated: 500 files, 10 MB per file, 64 MB total, narrowed to the host's publish limits. |
| `GitImportRepository` | no (permission prompt) | Copies a repository's files into the agent's own session folder, so `Read`/`Write`/`Edit`/`Glob`/`Grep` work on them. Reuses an existing Studio working copy when one matches the provider and branch; folder `.git` is skipped, and the copy is capped at 2000 files, 32 MB total and 4 MB per file. |
| `GitOpenChangeRequest` | no (fresh prompt on **every** call) | Copies the edited files into a Studio working copy, creates a **new** branch from the imported base commit, commits there through `RepoBridgeCommitUseCase` (review + secret scanner), and opens a pull request. Returns the PR URL. |

`GitCreateRepository` (`GitCreateRepositoryTool.kt`) validates and
measures the source **before** the repository is created, so a refused source
never leaves a stray repository behind. It never calls a fork or template
endpoint: the result has no upstream link. A private source is refused when
the new repository would be public, and a failure during upload leaves the
branch at the initial commit rather than a half-applied project — on GitHub every
blob is created before the tree and the commit, and on Gitea the whole copy is one
atomic `change-files` call. The seeded files are sent as `upsert`, because the
auto-generated README may share a path with a file from the copied project; the
repository was created a moment earlier, so there is no third-party content to protect.

`GitOpenChangeRequest` (`GitPublishTool.kt`) enforces four rules in code,
not by prompting the model:

1. **New branch every time.** `newBranchProblem` refuses `main`/`master`, the
   imported base branch, any existing remote branch and any name outside
   `type/slug` with an allowed prefix. `RepoBridgeCommitUseCase.commitNewBranch`
   re-checks the name and only ever creates a branch.
2. **Pull request only.** The push and the create-change-request call happen in
   one invocation; there is no path that advances a branch without opening a PR,
   and no merge API call anywhere in the agent tools.
3. **A tap per push.** The tool returns `requiresFreshApproval() = true`, so
   `PermissionChecker` never honours a remembered grant, the dialog hides
   "Always allow", and Auto-approve mode still asks for this tool.
4. **No force-push.** GitHub's `updateRef` is always called with `force = false`
   and the expected base commit; Gitea's `change-files` never sends `force_push`
   and validates the blob sha of every updated or deleted file. A moved branch or
   a file changed remotely fails as a conflict.

The base branch must still point at the commit recorded at import time — the
tool refuses otherwise, so a publish can never revert commits that landed after
the import. A file over 10 MB, or a folder beyond the writer's publish limits,
aborts the publish instead of shipping a partial change: a skipped file would
silently be missing from the pull request. On GitHub a branch created for a push
that then fails is deleted again only while it still points at the base commit; a
branch that received the commit is left in place and the error names it.

**What the agent cannot do, by design:** merge, approve or comment on a pull
request, dispatch a workflow, delete a branch, or force-update a ref. Those
calls live in `WorkspaceService` and the provider drivers and are reached only
from Studio's own screens, where the user taps them.


## Telegram command centre

Studio can be driven from a Telegram bot. It is an optional foreground service, off by
default, and it is the only part of Studio that answers without a screen in front of you.

```
Telegram chat ──getUpdates (long poll)──> TelegramBotService
                                             │
       paired chat? ──no──> one "not paired" line, nothing else
             │yes
             ▼
        TelegramCommandRouter ──> GitProvider / BuildStudio / ArtifactVault
             │
             └──> reply (text, or a vault APK as a document)
```

| Concern | Implementation |
| --- | --- |
| Token | `TelegramBotTokenStore` — AES-GCM with the same `TokenCipher` as the git tokens, associated data `sherwood-wta/telegram-bot/v1`. Never logged, never in an Intent. |
| Settings | `TelegramSettings` — enable switch, paired chats, "allow builds from chat", pairing code, last handled update id, cached bot username. |
| Pairing | Only a paired chat may send commands. Studio shows a six-digit, single-use code valid for 10 minutes; `/pair <code>` in the bot chat binds that chat. A wrong code is not retried for you — the code is consumed only on success, and it expires. |
| Polling | `TelegramBotService` (foreground, `specialUse` subtype `telegram_bot`), `getUpdates` with a 25 s long poll. The update offset is persisted **before** a command is handled, so a crash cannot replay it. |
| Failures | A rejected token stops the service and clears the enable switch (retrying cannot help). `409 Conflict` backs off 30 s. `429` waits Telegram's own `retry_after`. Network errors back off 5 s → 60 s. |
| Commands | `/status`, `/repos`, `/repo owner/name`, `/runs [branch]`, `/build <branch> [workflow]`, `/vault`, `/apk`, `/version`, `/help`, `/pair <code>`. Unknown commands get the command name back. |
| Remote builds | `/build` is gated by its own switch. It dispatches the workflow, keeps polling in the background and reports the conclusion and the number of imported artifacts in the same chat. It is the **only** remote write this module performs: push, merge and pull requests stay behind the in-app approval dialogs. |
| Notifications | `BuildEvents` (a `SharedFlow` in `core/studio`) is published when `buildBranch` finishes; the service forwards it to every paired chat. |
| Files | `/apk` uploads the newest vault APK as a document when it is under 45 MB, and falls back to a text description when it is not. |
| Limits | 12 commands per chat per minute; replies capped at 3,800 characters; arguments capped at 200 characters. |

The bot never merges, pushes, deletes a repository or changes settings. A leaked token
therefore gives an attacker control of a chat the phone has not paired, which receives a
single "not paired" line.

## Agent profiles

`core/agent/profile/AgentProfile` turns the previously implicit "how the agent runs"
decisions into data: preferred model, temperature, step budget, a prompt addendum, a tool
allowlist, and a runner id. `AgentProfileStore` persists them; the default profile is the
pre-profile behaviour, so upgrading changes nothing.

- **Tool subset** — `ToolRegistryFactory.build(hasImageModel, allowed)` filters the
  registry, so a tool outside the allowlist cannot be executed even if a model asks for
  it by name.
- **Prompt** — the addendum is rendered as its own `# Profile instructions` section after
  the built-in behaviour rules, so it can sharpen behaviour without editing them.
- **Runner** — `AgentRunner` is the interface `AgentEngine` implements and
  `AgentService.AgentRequest.runner` overrides. An alternative runner (a local instruction
  executor, an Open-Code-style driver) that emits the same `AgentEvent` stream inherits
  persistence and the whole UI unchanged.

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
- `GitToolsTest` — agent git tools: repository identifier parsing, the import
  copy guard rails (`.git`, oversized files, symlink escape) and their
  registration and read-only flags.
- `GitPublishTest` — the publish guard rails: branch derivation and the
  refusals for base/protected/unprefixed names, the session copy (metadata,
  `.git`, `.changes`, symlink escape), registration as a write tool, and the
  approval gate (a remembered "Always allow" never applies to a push).
- `GitCreateRepositoryTest` — repository-name and description rules, visibility
  default, the copy scan (plumbing, every supported host's CI files, symlink
  escape, caps refused rather than truncated), registration and the approval gate.
- `GiteaProviderTest` — Gitea/Forgejo JSON mapping and request shapes, including
  the write endpoints (create repository on `main`, branch from an exact commit,
  pull request, merge strategy, review verdict, workflow dispatch and the
  dispatch-unavailable failure).
- `GiteaCommitWriterTest` — the atomic `change-files` publish: `new_branch` when a
  branch is asked for, base64 content, the blob sha on updates and deletes, no
  `force_push`, conflicts mapped from a refused precondition, limits enforced
  before the network, and the paginated base tree.
- `StudioBuildSourcesTest` — release and release-asset parsing (the API download
  URL is required, non-`uploaded` assets are refused), the dispatched-run
  selection rule, and the vault export name / MIME type.
- `TelegramHarnessTest` — the harness's access rules: command parsing (`@BotName`
  suffix, bounded arguments, plain text ignored), the bot-token format gate, the pairing
  handshake (single use, wrong code, expiry, unpair), and cross-host run terminality
  (Gitea's `success`/`failure` statuses end the poll that GitHub's `completed` word would
  never end).
- `GitProviderSeamTest` — the provider seam: repository-reference parsing (exactly
  two segments, no traversal), per-provider token formats, credential-slot derivation
  (HTTPS only, port-aware), the registry refusing an unimplemented or misconfigured
  provider instead of falling back to GitHub, and both drivers' capability declarations.

Artifact signing, keystore import and installation need a device; they are
exercised manually, not in unit tests.
