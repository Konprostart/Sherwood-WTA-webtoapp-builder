<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Sherwood Studio" />

# Sherwood Studio

### An on-device Android development studio — build, ship, and manage APKs from your phone.

Sherwood Studio turns a website, a static web project, or a full server project into a
signed Android APK without a desktop, a build server, or an account. It is a complete
workshop: an editor, on-device runtimes, a Git client, a release vault, and an AI agent
that can drive all of it.

[![License: Unlicense](https://img.shields.io/badge/License-Unlicense-blue?style=for-the-badge)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/releases)
[![Docs](https://img.shields.io/badge/Docs-konprostart.github.io-8A2BE2?style=for-the-badge)](https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/)

</div>

---

## Why it exists

Most "website to app" tools wrap a URL and stop there. Sherwood Studio keeps going: the
same app that previews your project also runs the runtimes, signs the artifact, publishes
the repository, and answers questions about the build — all on the device in your hand.

| What you get | What that means in practice |
| --- | --- |
| **On-device APK export** | The APK is built, aligned, and signed locally. No upload, no remote worker. |
| **Real server runtimes** | Node.js, PHP, Python, Go, and WordPress are executed as native binaries from app storage. |
| **A studio, not a form** | Git hosting, branch and pull-request management, CI builds, and a local APK/AAB vault. |
| **An agent that can act** | 65 built-in tools covering app lifecycle, export, ports, files, and git hosting. |
| **A second opinion** | The floating Sidekick assistant explains errors, improves prompts, and reads screenshots. |
| **Remote control** | A paired Telegram bot can report status, list runs, and trigger builds. |
| **Offline model support** | Local Ollama endpoints work as first-class models, so the agent can run without a cloud provider. |

---

## Zero-Leak architecture

Sherwood Studio is deliberately split in two, and the split is enforced by the build —
not by convention.

```text
┌──────────────────────────────────────────────────────────────┐
│  Host app  (com.sherwood.wta)                                │
│  Editor · Studio · Vault · Telegram · Agent · Sidekick       │
│  needs: network, camera, microphone, notifications           │
└───────────────────────────┬──────────────────────────────────┘
                            │ exports
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  Generated app  (the shell template, synced from host code)   │
│  WebView runtime · local servers · the user's own config      │
│  no editor, no agent loop, no Telegram, no Studio creds       │
└──────────────────────────────────────────────────────────────┘
```

- Runtime Kotlin is authored **once**, under `app/`, and synced into `shell/` by a curated
  include list. Host-only packages (Studio, Vault, Telegram, Sidekick, the agent) are
  simply not on that list, so they cannot reach a generated APK.
- The shell consumes a generated **string subset** and a generated **DEX**, not the host's
  full code and strings.
- Permissions are feature-driven: generated apps request what their features need, and
  the host's own permissions never leak into the template.
- Every release is checked against this boundary before publication — see
  [`docs/developer/shell-sync.md`](docs/developer/shell-sync.md).

---

## Features

### Build

- Twelve app types: Web, Multi-Web, HTML, Frontend, Gallery, Media, Node.js, PHP, Python,
  Go, WordPress, and Offline pack.
- Incremental export with a content-keyed cache (`FULL` / `CONTENT_OVERLAY` /
  `REUSE_UNSIGNED`); encrypted builds always rebuild in full.
- Optional feature stacks (for example AdMob) are grafted into generated APKs only when the
  export config enables them — disabled stacks cost zero bytes.
- Play-ready `.aab` output alongside sideloadable APKs, from the same source tree.

### Work

- **Studio** — GitHub, Gitea, and Forgejo hosting: repositories, branches, pull-request
  inbox, CI pipeline runs, and artifact import.
- **Artifact vault** — every imported APK/AAB is stored with its SHA-256 and provenance,
  verified on read, and written atomically.
- **Agent** — up to 65 tools with per-action permission prompts; plan mode for multi-step
  work; image-capable models unlock the imagery tools.
- **Sidekick** — a non-modal floating assistant with screenshot, camera, file, and
  dictation input. It explains and advises; it does not silently change your project.
- **Telegram command centre** — a paired private chat can read status, list runs, and
  start builds; push, merge, and publish stay inside the app.

### Run

- Hardened networking: DNS-over-HTTPS, per-app proxies, ad blocking, userscripts, and MV3
  extensions.
- Optional GeckoView (Firefox) engine for the cases the system WebView cannot serve.
- Multi-Web layouts with per-site sessions, page recovery after a renderer death, and
  drawer/card navigation.

### Talk

- **Ten UI languages**, including Arabic RTL.
- **Local models** through an Ollama-compatible endpoint, plus a strict Whisper endpoint
  driver for dictation in languages your phone's speech service does not cover.

---

## Install

Download the latest signed APK from
[GitHub Releases](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/releases).

| Artifact | Package | Use |
| --- | --- | --- |
| `Sherwood-WTA-<version>.apk` | `com.sherwood.wta` | GitHub / sideload |
| `Sherwood-WTA-<version>-gplay.apk` | `com.sherwood.wta.play` | Google Play track |
| `Sherwood-WTA-<version>-gplay.aab` | `com.sherwood.wta.play` | Play upload bundle |

Releases are signed with the same key across versions, so a new build installs over the
previous one without uninstalling and without losing data.

Requirements: Android 7.0 (API 24) or newer.

---

## Build from source

Requirements: JDK 17. The Gradle wrapper pins the exact Gradle version, so no separate
Gradle install is needed.

```bash
git clone https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder.git
cd Sherwood-WTA-webtoapp-builder
./gradlew assembleStandardDebug
```

Two distribution flavors share all code and build rules:

| Flavor | `applicationId` | Contents |
| --- | --- | --- |
| `standard` | `com.sherwood.wta` | GitHub / sideloaded builds |
| `gplay` | `com.sherwood.wta.play` | Google Play build, includes Google services |

```bash
./gradlew :app:assembleStandardRelease        # signed release APK
./gradlew :app:assembleGplayRelease           # signed Play-track APK
./gradlew :app:bundleGplayRelease             # signed AAB
./gradlew :app:assembleStandardRelease -Pfdroid=true   # unsigned F-Droid build
```

The build compiles the host app and the shell template. The template is produced by
`:shell:assembleRelease` and copied into `app/src/main/assets/template/` by
`:app:syncShellTemplateApk`; a stale template is the most common reason a preview works
while an exported APK does not.

### Local signing

Configure a keystore in `local.properties`:

```properties
signing.storeFile=/absolute/path/to/release.jks
signing.storePassword=…
signing.keyAlias=…
signing.keyPassword=…
```

The same values are read from `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` on CI. Never commit a keystore or
`local.properties`.

### Checks worth running

```bash
./gradlew :app:compileStandardDebugKotlin -x syncCloneHostDex --no-configuration-cache
./gradlew :app:testStandardDebugUnitTest --no-configuration-cache
./gradlew :app:checkConfigFieldDrift --no-configuration-cache
./gradlew :shell:assembleRelease :app:syncShellTemplateApk --no-configuration-cache
```

`checkConfigFieldDrift` is the gate that catches an editor setting whose name never
reaches the shell config class. A drifted field does not fail the build by itself — it
silently does nothing in exported apps, which is why the check is mandatory.

---

## Release process

Releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml)
when a `v*` tag is pushed. The workflow refuses to publish when the tag does not match
`versionName`, when a signing secret is missing, or when the produced signature does not
match the pinned certificate.

1. Set `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Commit and push the branch.
3. Wait for `Android CI Build` to pass in full (both compile jobs and the whole unit-test
   suite).
4. Tag the commit and push the tag.

```bash
git tag v2.10.6
git push origin v2.10.6
```

The keystore is the only key that can update installed copies. Losing it means existing
installs can never be updated; keep an offline backup.

---

## Project layout

| Path | Role |
| --- | --- |
| `app/` | Host app: editor UI, export pipeline, Studio, Vault, agent, Sidekick, runtimes. |
| `shell/` | Runtime template compiled into the generated-app APK. |
| `feature-stacks/` | Optional dependency bundles grafted into exports on demand. |
| `clone-host/` | Host-side APK clone / identity reshape support. |
| `modules/` | Module Market catalog. |
| `sample-bundles/` | Heavy sample dependency packs fetched on demand. |
| `docs/` | Documentation site source (VitePress). |
| `scripts/` | Build helpers and gates. |

---

## Documentation

- [Getting started](docs/guide/getting-started.md)
- [Architecture](docs/developer/architecture.md)
- [Studio](docs/developer/studio.md)
- [Shell sync and the host/export boundary](docs/developer/shell-sync.md)
- [Export pipeline](docs/developer/export-pipeline.md)
- [Agent guide](docs/guide/more-features/agent.md)
- [Contributor guide](.github/CONTRIBUTING.md)
- [Agent-facing architecture constraints](AGENTS.md)

The published site lives at
https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/.

---

## Contributing

| Lane | What you do | Guide |
| --- | --- | --- |
| `modules/` | Publish a module to the in-app market | [modules/README.md](modules/README.md) |
| Issues | Report a bug or request a feature | [GitHub Issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues) |
| Code | Fix a bug or build a feature in the app | [CONTRIBUTING.md](.github/CONTRIBUTING.md) |
| Agents | Architecture and packaging constraints | [AGENTS.md](AGENTS.md) |

Issues and pull requests are written in English so they stay searchable for everyone.
Read AGENTS.md before changing export, shell membership, or config fields — those paths
have failure modes that compile cleanly and still ship broken apps.

---

## License

Sherwood Studio is released into the public domain under [The Unlicense](LICENSE). You may
use, copy, modify, and distribute it for any purpose, without conditions.

Third-party components keep their own licences; the full list, the licence assessment, and
the attribution for the project's public-domain lineage are in [NOTICE.md](NOTICE.md).
