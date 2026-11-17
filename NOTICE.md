# NOTICE

Sherwood Studio is released into the public domain under [The Unlicense](LICENSE).
This file lists third-party components, their licences, and the licence assessment
behind the choices made for this fork. It is informational, not legal advice.

## Project licence

| Component | Licence |
| --- | --- |
| Sherwood Studio (this repository) | The Unlicense |

## Third-party components shipped in the Android app

| Component | Licence | Notes |
| --- | --- | --- |
| AndroidX (core, appcompat, activity, lifecycle, room, datastore, webkit, documentfile) | Apache-2.0 | Permissive |
| Jetpack Compose (UI, Material 3, Material Icons) | Apache-2.0 | Permissive |
| Google Material Components for Android | Apache-2.0 | Permissive |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | Permissive |
| Koin | Apache-2.0 | Permissive |
| OkHttp, okhttp-dnsoverhttps | Apache-2.0 | Permissive |
| Gson | Apache-2.0 | Permissive |
| Coil | Apache-2.0 | Permissive |
| Bouncy Castle (bcprov, bcpkix) | Bouncy Castle Licence | MIT-style permissive |
| Apache Commons Compress | Apache-2.0 | Permissive |
| XZ for Java | Public Domain | Permissive |
| Android apksig (`com.android.tools.build:apksig`) | Apache-2.0 | Used for APK signing/verification |
| Mozilla GeckoView | MPL-2.0 | Weak, file-level copyleft. Already present for the GeckoView runtime; not modified. MPL-2.0 obligations apply only to modified GeckoView files, of which there are none. |
| Chromium Cronet embedded | Apache-2.0 | Permissive |
| ZXing core | Apache-2.0 | Permissive |
| Protocol Buffers (protobuf-javalite) | BSD-3-Clause | Permissive |
| Firebase Cloud Messaging, Google Play services | Google APIs Terms of Service | Proprietary. Used only by the `gplay` flavor and only with a developer-owned Firebase project. |
| Gradle, Android Gradle Plugin, KSP | Apache-2.0 | Build-time only |

No GPL, AGPL or LGPL dependency was added by this work. The only weak-copyleft
component is the pre-existing GeckoView (MPL-2.0); it is consumed as an unmodified
artifact, so its file-level copyleft is satisfied by keeping the artifact intact.

## JGit: assessed and deliberately not used

The GitHub Studio needs branching, checkout, merge, history and diff. JGit is the
obvious library, but:

- JGit 5.x and newer use `java.nio.file`, which is API 26+ on Android.
- The workarounds commonly suggested (core library desugaring of NIO, reflective
  shims, or raising `minSdk`) either do not exist for these APIs or would drop
  Android 6/7 users.
- This project keeps `minSdk` 24 on purpose (see `AGENTS.md`).

The alternative implemented here is the GitHub Git Data API, which builds tree,
blob and commit objects server-side. It provides the same operations (branch,
checkout by re-import, merge through GitHub's 3-way merge, commit history, diff via
a bounded local unified diff) without a local `.git` and without changing
`minSdk`. JGit's licence (EDL-1.0) is permissive, so the decision is purely
technical.

## Upstream provenance

This repository descends from the WebToApp Builder project and preserves its full
history (root commits `36728b1d` and `6a646e35`, author `shiaho`; the README at
that point pointed at `github.com/shiahonb777/web-to-app`, which GitHub now
redirects to `github.com/shiaho777/web-to-app`). GitHub's repository metadata does
not record the fork relation (`fork: false`, no parent), so provenance is
established from the commit history rather than from the API.

The upstream project is credited here by name because that is what attribution
means. The product shipped from this repository is Sherwood Studio: the branding,
package identity, and feature set below are its own, while the public-domain
lineage stays acknowledged.

### Changes made by Sherwood Studio

This fork is not a copy of the upstream product. The work layered on top of it
includes, in addition to the rebrand (`com.sherwood.wta`, product name, icons, and
documentation):

- **Studio**: a git-provider abstraction over GitHub, Gitea, and Forgejo;
  repository/branch management; pull-request inbox; CI pipeline runs; artifact
  import; and a local APK/AAB vault with SHA-256 verification and atomic writes.
- **Agent platform**: agent profiles, a runner seam, an offline Ollama driver, and a
  tool-permission layer.
- **Sidekick**: a non-modal floating assistant with image, camera, file, and
  dictation input, backed by a strict Whisper-endpoint driver.
- **Telegram command centre**: a paired-chat bot with inline controls and CI
  notifications.
- **Multi-Web**: drawer and card navigation, per-site sessions, and renderer-death
  recovery.
- **Hardening**: streaming encryption, bounded decodes, URL/redirect policy, and a
  host-only export boundary.

No upstream licence obligation is altered by these changes: the upstream code is
public domain under the `LICENSE` file, and every added third-party component keeps
the licence recorded in the tables above.

Licensing state of the upstream code, as recorded in this history:

- `LICENSE` in the root commit is **The Unlicense** (public-domain dedication), and
  the same file is still at the repository root.
- The upstream `README.md` carried an **MIT** badge at the same time. That
  contradiction exists in the upstream repository itself; it is recorded here
  rather than resolved, because only the copyright holder can settle which one was
  intended.

Both readings are permissive and neither imposes an obligation beyond keeping the
notice. Downstream consumers can therefore treat the upstream code as released
under the `LICENSE` file in this repository, while being aware of the badge
mismatch when auditing. This is a factual description of the files, not a legal
opinion.

## Secrets

- No token, private key, keystore or `local.properties` is committed.
- GitHub tokens are stored encrypted with the Android Keystore
  (`GitHubTokenStore`) and are only sent to the configured GitHub host over HTTPS.
- OAuth uses the Device Flow with a user-owned public client ID; no client secret
  exists in the app.
- `SecretScanner` runs over before a commit can be pushed by the repo bridge.

## Building the final APK/AAB

```bash
# Standard signed APK (release keystore from local.properties or env)
./gradlew :app:assembleStandardRelease

# Google Play flavor APK and bundle
./gradlew :app:assembleGplayRelease :app:bundleGplayRelease

# Unsigned F-Droid APK
./gradlew :app:assembleStandardRelease -Pfdroid=true
```

Release tags (`v*`) are built by `.github/workflows/release.yml`. The tag must
match `versionName`. A release publishes three artifacts plus a checksum manifest
directly on the GitHub Release page:

- `Sherwood-WTA-<version>.apk` — standard flavor
- `Sherwood-WTA-<version>-gplay.apk` — Google Play flavor
- `Sherwood-WTA-<version>-gplay.aab` — Android App Bundle for Play upload
- `SHA256SUMS.txt` — SHA-256 of exactly those three files

Both APKs are checked against the pinned release certificate before anything is
published, the bundle's JAR signature is verified, and the release is created as a
draft so a partially uploaded release is never visible. Workflow artifacts are
additional copies, not a substitute for the release assets.
