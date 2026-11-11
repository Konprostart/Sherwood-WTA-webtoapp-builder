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

This repository is an independent fork. The original WebToApp upstream is not
identified with a confirmed licence in this repository's history, so no claim is
made that upstream code is covered by a specific licence. Only code authored for
this fork and components listed above are represented here. Do not copy code from
projects whose licence is unclear.

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

# Google Play AAB
./gradlew :app:bundleGplayRelease

# Unsigned F-Droid APK
./gradlew :app:assembleStandardRelease -Pfdroid=true
```

Release tags (`v*`) are built by `.github/workflows/release.yml`. The tag must
match `versionName`; artifacts are `Sherwood-WTA-<version>.apk` and
`Sherwood-WTA-<version>-gplay.aab`.
