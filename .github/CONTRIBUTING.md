# Contributing to WebToApp

Thanks for taking the time to help. WebToApp moves fastest when contributions
stay **small and well-scoped** — pick one of the lanes below and ignore the
rest.

> **English**

This guide targets **WebToApp 2.6.9** (`versionCode 71`).

---

## Lanes

| You want to… | Go to | Effort |
| --- | --- | --- |
| Publish a JS/CSS module to the in-app **Module Market** | [`modules/README.md`](../modules/README.md) | hours |
| File a bug, request a feature, or ask a question | [GitHub Issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues) | minutes |
| Fix a bug or build a feature in the Android client | This guide ↓ | days |

If you're not sure which lane fits, open an issue or a discussion first. There
is no need to write code before there's agreement on the shape of the change.

---

## Module Market submissions

The fastest way to ship something useful to every WebToApp user is to publish
a module. The canonical Module Market guide lives in
[`modules/README.md`](../modules/README.md). Use that file for the full
schema, field rules, reviewer checklist, and CI validation details. The short
version here is only meant to help you pick the right contribution lane:

1. Fork the repo.
2. Add `modules/<your-plugin>/plugin.json` and `plugin.html` (one HTML file —
   page script in a `<script type="hcj/page">` block, the rest is the panel UI).
3. Add an entry to `modules/registry.json`.
4. Open a PR.

The market has **no backend**. Clients read `registry.json` and
`submissions.json`, and only merged modules show up in the catalog. Module
changes are validated in CI by `.github/scripts/ci/validate_modules.py`:

```bash
python3 .github/scripts/ci/validate_modules.py
```

---

## Code contributions

> The canonical working guide for AI coding agents and deep code
> contributors is [`AGENTS.md`](../AGENTS.md) — it documents how the editor,
> export pipeline, shell template, and runtime connect, the dual preview/export
> paths, config-field drift rules, packaging constraints, and common change
> recipes. Read it before any non-trivial change to the build, shell sync,
> export packaging, or config fields.

### Before you write code

- Search [issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues) for prior
  discussion of the same idea.
- For non-trivial changes, open an issue first describing the problem and the
  approach you have in mind. This is much cheaper than rewriting after review.
- **Avoid adding new dependencies.** The lists in `app/build.gradle.kts` and
  `shell/build.gradle.kts` are intentionally restrained — the project signs and
  packages APKs in-process, and the shell template pins `targetSdk = 28` on
  purpose because generated apps rely on fork+exec native runtimes. New
  dependencies need a strong justification, and host-only dependencies must
  never leak into the shell template.

### Local setup

You'll need:

- Android Studio Hedgehog or newer
- JDK 17
- The Gradle wrapper pins Gradle 9.4.1 — no system Gradle install required

```bash
git clone https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder.git
cd web-to-app
./gradlew assembleDebug
```

The repo has three Gradle modules: **`app`** (the full builder and host),
**`shell`** (the runtime template embedded in generated APKs), and
**`clone-host`** (the host code used by app cloning). Shared runtime code is
authored **only** under `app/` and synchronized into `shell/` at build time
(`shell/build/generated/` — never edit `shell/src` by hand; it is
regenerated and partly git-ignored).

Run the checks before submitting:

```bash
./gradlew :app:compileStandardDebugKotlin -x syncCloneHostDex --no-configuration-cache
./gradlew :app:testStandardDebugUnitTest --no-configuration-cache -PskipShellTemplateSync=true
./gradlew :app:checkConfigFieldDrift --no-configuration-cache
```

If you touched shell-synced runtime code or export packaging, also rebuild the
template you touched:

```bash
./gradlew :shell:assembleRelease :app:syncShellTemplateApk --no-configuration-cache
```

> Native code (`node_launcher`, `go_exec_loader`, the APK optimizer) builds
> via CMake per-ABI and needs the Android NDK + CMake installed through the
> SDK Manager. CI installs `cmake;3.22.1` and `ndk;28.2.13676358`.

### Where things live

| Area | Path |
| --- | --- |
| App types & central config (`AppType`, `WebApp`) | `app/src/main/java/com/sherwood/wta/data/model/` |
| On-device APK builder / signer | `app/src/main/java/com/sherwood/wta/core/apkbuilder/` |
| Server runtimes (Node / PHP / Python / Go / WordPress) | `app/src/main/java/com/sherwood/wta/core/{nodejs,php,python,golang,wordpress}/` |
| WebView engine, native bridge, fingerprint disguise | `app/src/main/java/com/sherwood/wta/core/{webview,engine,appearance}/` |
| Shell config + generated-APK entry points | `app/src/main/java/com/sherwood/wta/core/shell/` |
| Preview players (`ui/gallery/`, `ui/media/`) vs packaged players (`ui/shell/`) | `app/src/main/java/com/sherwood/wta/ui/` |
| Extension modules, Module Market, Agent | `app/src/main/java/com/sherwood/wta/core/{extension,market,agent}/` |
| Compose UI screens & design system | `app/src/main/java/com/sherwood/wta/ui/` |
| DI graph (source of truth) | `app/src/main/java/com/sherwood/wta/di/AppModule.kt` |

### Preview ≠ export: the rule behind most rejections

The host app (preview) and generated APKs (export) run **different code**:

| | Preview (`:app`) | Exported APK |
| --- | --- | --- |
| UI | `ui/gallery/`, `ui/media/`, `WebViewManager` live | `ui/shell/*` synced copy |
| Config | Editor / in-memory models | JSON assets via `ShellModeManager` |

A feature is done only when it works on **both** paths. Concretely, an editor
setting that must affect generated APKs has to flow through the full chain —
model → `ApkConfig` JSON → shell config → shell-synced runtime — and a
player-screen behavior has to be implemented in **both** the preview and the
shell composable. Three gates enforce this; run them, don't argue with them:

- `checkConfigFieldDrift` — model/JSON/shell field names must match
  (Gson silently drops mismatches).
- `WebViewConfigBooleanCoverageTest` — every new `WebViewConfig` Boolean must
  be listed in `flipAllBooleans()` and survive the export round-trip.
- `ShellUiParityTest` — every model field read by host player UI must also be
  read by shell player UI (or carry an explicit `allow` reason). Add your new
  per-type player pair to it.

### Coding conventions

WebToApp leans on the Kotlin and Jetpack Compose patterns already in the
codebase. A few rules worth calling out:

- **Comments explain *why*, not *what*.** Non-obvious decisions, cross-file
  contracts, and issue references (`#781`) get a short comment; clear names
  and small functions carry the rest. Don't narrate readable code.
- **Build new UI on the Wta design system.** Everything renders through
  `com.sherwood.wta.ui.design`. The older `Premium*` / `Enhanced*` / `Settings*`
  components are retained as permanent alias layers over the Wta internals —
  don't add new ones, and you don't need to rip them out. A build-time audit
  (`.github/scripts/audit_ui_design_system.py`, wired into `build.gradle.kts`) tracks
  legacy UI debt against `.github/scripts/ui_design_allowlist.txt`.
- **Reuse the design tokens** in `ui/design/WtaTokens.kt` for spacing, radius,
  alpha, and elevation. Don't hard-code numbers. Editor config cards share one
  layout grammar (see `AGENTS.md` recipe 12) — copy the neighbouring cards,
  don't invent your own, and verify on the emulator, not just by compiling.
- **Strings must cover all 10 supported languages.** UI copy lives in
  `core/i18n/` (facade `object Strings` in `Strings.kt` + `StringsA`…`StringsE`,
  one file per split object; split only for the JVM constant pool). Supported:
  Chinese, English, Arabic,
  Portuguese, Spanish, French, German, Russian, Japanese, Korean.
  Every new or changed user-visible `when (Strings.lang)` block **must** have
  real translations for **all 10** branches with no `else ->` — do not leave
  pt/es/fr/de/ru/ja/ko as English placeholders. Brand names and pure format
  tokens may match English. If you truly cannot translate one language, use
  English there and flag it in the PR.
- **Never load user-visible text from `R.string`.** Resource files only cover
  zh/en/ar, so lookups silently fall back to Chinese for the other 7 locales.
  `R.string` is reserved for non-localised resources.
- **No new top-level singletons** unless you discuss it first. The DI graph in
  `di/AppModule.kt` is the source of truth.
- **The Native Bridge is capability-gated.** Any method exposed to web content
  via `@JavascriptInterface` must be guarded by the per-capability allow-list
  (`NativeBridgeCapabilities`). Never expose a native capability to arbitrary
  pages without a gate. PRs that bypass this will be rejected.
- **Preview and packaged runtime are two paths.** See *Preview ≠ export*
  above. Shared runtime sources are authored only under `app/` and synced into
  `shell/` at build time — do not hand-edit anything under `shell/src`.
- **Avoid catching `Exception` to silence errors.** If recovery is impossible,
  log via `AppLogger` and re-throw or return a failed `Result`.

### Security

Anything touching the WebView, file IO, APK signing, or the native bridge has
downstream impact on **every generated app**, so it gets extra scrutiny.
Specifically, PRs will be declined if they:

- store or expose credentials/secrets to web content,
- widen the native surface exposed to arbitrary pages without a capability
  gate,
- weaken APK signing, isolation, or the fingerprint-disguise defaults,
- bundle unrelated changes inside a large diff.

### Commit messages

We don't enforce Conventional Commits, but a clear subject line and a wrapped
body that explains *why* the change exists make reviews much faster. Example:

```
ModuleMarket: cache registry.json for an hour

Repeatedly hitting raw.githubusercontent.com on every screen open is wasteful
and triggers GitHub's anonymous rate limit on slow connections. Cache the
parsed registry under cache/module_market/ and treat anything fresher than
an hour as authoritative; the refresh button always bypasses the cache.
```

### Pull requests

- Branch from `main`. Keep PRs focused — one logical change per PR.
- Name branches `type/slug` (`fix/…`, `feat/…`, `refactor/…`, `docs/…`) —
  no tool/agent namespaces like `codex/…`.
- **Write Issues and PRs in English** — titles, bodies, and review threads.
- Describe the user-visible effect in the PR body, not just the code change.
- The standard loop is Issue → branch → PR (`Fixes #N`) → green CI → merge.
  Issues close on merge, never on PR open or red CI. Opening the PR is not
  the end of the loop: stay until `check` is green and the PR is merged.
- If your PR touches the build system, native code, or APK packaging, attach
  the output of the template rebuild above (or note the failure if it fails
  on your machine).
- CI runs on every PR. A green CI is required before merge.
- Never commit secrets, keystores, `local.properties`, or IDE/cache junk.

### Reviewer expectations

Maintainer reviews look for:

- **Correctness.** Does the change do what the PR description says? Was it
  verified by execution (tests, build output), not just by reading?
- **Safety.** See *Security* above.
- **Scope discipline.** Drive-by refactors expand the review surface. Land
  them in a separate PR.
- **Style fit.** See *Coding conventions* above.
- **Both paths.** Preview-only features and export-only wiring are the two
  classic failure modes — see *Preview ≠ export* above.

---

## Code of conduct

Feedback should be specific: name the code, explain the problem, and suggest a
fix where you can. Be respectful to other contributors and users.
