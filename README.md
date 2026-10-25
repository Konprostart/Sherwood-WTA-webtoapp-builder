<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Sherwood WTA icon" />

# Sherwood WTA

### Build Android APKs from web projects, directly on your phone.

Sherwood WTA is an on-device builder: it wraps a website, a static web project, or a
server project (Node.js, PHP, Python, Go, WordPress) into an Android APK, signs it, and
exports it, without a desktop computer or a build server.

[![License: Unlicense](https://img.shields.io/badge/License-Unlicense-blue?style=for-the-badge)](LICENSE)
[![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/releases)

</div>

## About this project

Sherwood WTA is an independent project released into the public domain under
[The Unlicense](LICENSE). It has no runtime dependencies on third-party
repositories, accounts, or community channels, and uses its own package name
(`com.sherwood.wta`).

## Features

- Preview and export web projects, single-page apps, and server projects as APKs.
- Server runtimes (Node.js, PHP, Python, Go, WordPress) run on the device.
- Userscripts and browser-extension modules, a native bridge for web pages, and per-app
  build options (signing, isolation, background run, notifications).
- Optional AI agent with user-supplied API keys.

See [`docs/`](docs/index.md) for the user guide, developer documentation, and extension
reference.

## Build from source

Requirements: Android Studio Hedgehog or newer, JDK 17. The Gradle wrapper pins Gradle 9.4.1.

```bash
git clone https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder.git
cd Sherwood-WTA-webtoapp-builder
./gradlew assembleDebug
```

Two distribution flavors share all code and build rules:

| Flavor | `applicationId` | Use |
| --- | --- | --- |
| `standard` | `com.sherwood.wta` | GitHub / sideloaded releases |
| `gplay` | `com.sherwood.wta.play` | Google Play (includes Google services) |

```bash
./gradlew assembleStandardRelease   # signed release APK
./gradlew bundleGplayRelease        # signed AAB for Google Play
./gradlew :app:assembleStandardRelease -Pfdroid=true   # unsigned F-Droid APK (no Google services)
```

Signed release builds need your own keystore. Locally, configure it in `local.properties`
(`signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword`).
In CI, the same values come from environment variables (`ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
Do not commit keystores or `local.properties`.

## Release signing

Releases are built by `.github/workflows/release.yml` when a tag `v*` is pushed. The
release keystore lives only in the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Local
builds read the same values from `local.properties` or the `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` environment
variables. Do not commit keystores or `local.properties`.

Publish a release: set `versionName` and `versionCode` in `app/build.gradle.kts`, commit,
then push a tag with the same version (`git tag v2.8.3 && git push origin v2.8.3`). The
workflow fails if the tag does not match `versionName`, or if any secret is missing.
Artifacts: `Sherwood-WTA-<version>.apk` and `Sherwood-WTA-<version>-gplay.aab`.

Keystore rotation or loss: the keystore is the only key that can update installed copies.
Losing it means existing installs cannot be updated; keep an offline backup of it.

## Contributing

| Lane | What you do | Guide |
| --- | --- | --- |
| `modules/` | Publish a community module to the in-app market | [modules/README.md](modules/README.md) |
| Issues | Report a bug or request a feature | [GitHub Issues](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder/issues) |
| Code | Fix a bug or build a feature in the Android client | [CONTRIBUTING.md](.github/CONTRIBUTING.md) |
| Agents | Architecture, export pipeline, and packaging constraints | [AGENTS.md](AGENTS.md) |

## License

[The Unlicense](LICENSE). The software is in the public domain; you may copy, modify,
publish, and distribute it for any purpose. Third-party dependencies keep their own
licenses.
