package com.sherwood.wta.core.studio

/**
 * Third-party components shipped by this app, with their licence. Kept as plain
 * data so the licences screen and NOTICE.md can be compared by a unit test.
 *
 * This list is informational and must not be read as legal advice. A component
 * added to `app/build.gradle.kts` belongs here (and in NOTICE.md) in the same
 * change; missing entries are a review defect.
 */
data class ThirdPartyNotice(
    val name: String,
    val licence: String,
    val note: String? = null
)

object ThirdPartyNotices {

    const val PROJECT_LICENCE = "Unlicense"

    val components: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice("AndroidX (core, appcompat, activity, lifecycle, room, datastore, webkit, documentfile)", "Apache-2.0"),
        ThirdPartyNotice("Jetpack Compose (UI, Material 3, Material Icons)", "Apache-2.0"),
        ThirdPartyNotice("Google Material Components for Android", "Apache-2.0"),
        ThirdPartyNotice("Kotlin standard library and kotlinx.coroutines", "Apache-2.0"),
        ThirdPartyNotice("Koin", "Apache-2.0"),
        ThirdPartyNotice("OkHttp and okhttp-dnsoverhttps", "Apache-2.0"),
        ThirdPartyNotice("Gson", "Apache-2.0"),
        ThirdPartyNotice("Coil", "Apache-2.0"),
        ThirdPartyNotice("Bouncy Castle (bcprov, bcpkix)", "Bouncy Castle Licence"),
        ThirdPartyNotice("Apache Commons Compress", "Apache-2.0"),
        ThirdPartyNotice("XZ for Java", "Public Domain"),
        ThirdPartyNotice("Android apksig (com.android.tools.build:apksig)", "Apache-2.0"),
        ThirdPartyNotice("Mozilla GeckoView", "MPL-2.0"),
        ThirdPartyNotice("Chromium Cronet embedded", "Apache-2.0"),
        ThirdPartyNotice("ZXing core", "Apache-2.0"),
        ThirdPartyNotice("Protocol Buffers (protobuf-javalite)", "BSD-3-Clause"),
        ThirdPartyNotice(
            "Firebase Cloud Messaging and Google Play services",
            "Google APIs Terms of Service",
            "Used only by the gplay flavor and only with a developer-owned Firebase project."
        ),
        ThirdPartyNotice(
            "JGit",
            "EDL-1.0",
            "NOT SHIPPED. Its java.nio dependency needs minSdk 26; this project keeps minSdk 24 and uses the GitHub Git Data API instead."
        )
    )
}
