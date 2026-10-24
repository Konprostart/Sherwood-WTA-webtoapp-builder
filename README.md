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

## About this fork

Sherwood WTA is an independent fork of
[shiaho777/web-to-app](https://github.com/shiaho777/web-to-app), originally created by
shiaho and released into the public domain under [The Unlicense](LICENSE). This fork
removes runtime dependencies on the original author's repositories, accounts, and
community channels, and uses its own package name (`com.sherwood.wta`).

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

Signed release builds need your own keystore, configured in `local.properties`
(`signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword`).
Do not commit keystores or `local.properties`.

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
