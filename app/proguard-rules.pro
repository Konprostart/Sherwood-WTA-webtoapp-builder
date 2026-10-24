# Sherwood WTA ProGuard Rules
#
# Strategy: enable code shrinking (remove unused code) + resource shrinking (remove unused resources),
# obfuscation and aggressive optimization are disabled. This "stability first" configuration has been repeatedly validated after production incidents,
# still keeps 30%+ size savings, but fully avoids reflection / generics / ServiceLoader / JNI class pitfalls.
#
# Trade-offs:
#   - shrink:    kept; removes unused classes/methods (~25% size reduction)
#   - obfuscate: off (pointless for an open-source project; introduces Gson/Koin/reflection bugs)
#   - optimize:  some aggressive optimizations disabled (default shrinking kept; avoids NPEs caused by inlining)
#
# Troubleshooting:
#   1. ./gradlew :app:assembleRelease -PandroidProguardPrintUsage=true
#      writes everything R8 removed to build/outputs/mapping/release/usage.txt
#   2. ./gradlew :app:assembleRelease -PandroidProguardPrintSeeds=true
#      writes everything that is explicitly kept
#   3. Map the crash stack back using build/outputs/mapping/release/mapping.txt

-dontobfuscate

# Disable a group of optimization passes that tend to break reflection / Compose / coroutine semantics
# These are R8 optimization passes with a history of recurring bugs; disabling them has negligible size cost
-optimizations !class/merging/*,!field/*,!method/marking/static,!method/inlining/*,!code/allocation/variable
-optimizationpasses 1

# ============================================================
# Debugging / readable crash stacks
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,RuntimeInvisibleTypeAnnotations
-keepattributes AnnotationDefault
-keepattributes MethodParameters

# Make crash stacks print pre-R8-rename class names / line numbers
-renamesourcefileattribute SourceFile

# ============================================================
# Android components — referenced from the manifest; names must be kept
# ============================================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends androidx.work.ListenableWorker
-keep public class * extends android.app.Application
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.lifecycle.ViewModel
-keep public class * extends androidx.lifecycle.AndroidViewModel

# ============================================================
# General reflection — Parcelable / Serializable / enum / native
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# WebView @JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# View XML inflate / setOnClick reflective callbacks
-keepclassmembers class * extends android.view.View {
    void set*(***);
    *** get*();
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# ============================================================
# Kotlin
# ============================================================
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { public <methods>; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keepclassmembers class kotlin.coroutines.jvm.internal.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Kotlin Coroutines — ServiceLoader loads Dispatchers.Main
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
# Coroutines debugging / exception hooks
-keepnames class kotlinx.coroutines.flow.** { *; }

# ============================================================
# Project code — kept in full (open source + heavy reflection)
# ============================================================
-keep class com.sherwood.wta.** { *; }
-keepclassmembers class com.sherwood.wta.** { *; }
-keep enum com.sherwood.wta.** { *; }
-keep interface com.sherwood.wta.** { *; }

# Synthetic constructors of data classes (with default params) — required for Gson deserialization
-keepclassmembers class com.sherwood.wta.data.model.** {
    <init>(...);
}

# ============================================================
# Room — KSP-generated _Impl classes are already covered by com.sherwood.wta.**
# ============================================================
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ============================================================
# Gson — serialization / deserialization via reflection
# ============================================================
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class * implements com.google.gson.InstanceCreator
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.JsonAdapter <fields>;
    @com.google.gson.annotations.Expose <fields>;
}

# Gson TypeToken — R8 full mode strips the Signature attribute of `object : TypeToken<List<...>>() {}`
# of the anonymous subclass, causing IllegalStateException at runtime.
# Reference: https://github.com/google/gson/blob/main/Troubleshooting.md#r8
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ============================================================
# Koin DI — heavy reflection + ServiceLoader
# ============================================================
-keep class org.koin.** { *; }
-keepclassmembers class * {
    public <init>(org.koin.core.scope.Scope);
}
-keepclassmembers class * extends org.koin.core.module.Module { *; }
-dontwarn org.koin.**

# ============================================================
# OkHttp / Okio — Platform reflection detects the OS security stack
# ============================================================
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.internal.publicsuffix.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================
# Coil — ServiceLoader loads fetcher / decoder / mapper
# ============================================================
-keep class coil.util.** { *; }
-keep class coil.fetch.** { *; }
-keep class coil.decode.** { *; }
-keep class coil.map.** { *; }
-dontwarn coil.**

# ============================================================
# apksig — reflectively reads @Asn1Class/@Asn1Field annotations to serialize PKCS#7
# ============================================================
-keep class com.android.apksig.** { *; }
-keepclassmembers class com.android.apksig.** { *; }
-keep @com.android.apksig.internal.asn1.Asn1Class class *
-keepclassmembers class * {
    @com.android.apksig.internal.asn1.Asn1Field *;
}
-dontwarn com.android.apksig.**

-keep class com.android.aapt.** { *; }
-keepclassmembers class com.android.aapt.** { *; }
-keep class com.android.bundle.** { *; }
-keepclassmembers class com.android.bundle.** { *; }

# ============================================================
# GeckoView — heavy JNI / annotation reflection
# ============================================================
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keepclassmembers class * extends org.mozilla.geckoview.GeckoSession$* { *; }
-dontwarn org.mozilla.geckoview.**
# GeckoView's bundled ExoPlayer references Checker Framework annotations.
# Play Services used to pull those types in; the F-Droid flavor excludes GMS.
-dontwarn org.checkerframework.**

# ============================================================
# ZXing — reflectively looks up encoding formats (core only; embedded removed)
# ============================================================
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# BillingClient — AIDL stub
# ============================================================
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }
-keep class com.google.android.gms.internal.** { *; }
-dontwarn com.android.billingclient.**

# ============================================================
# Credentials API + GoogleId — reflective ID Token parsing
# ============================================================
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.**

# ============================================================
# DataStore Preferences
# ============================================================
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ============================================================
# Compress / xz — ServiceLoader loads formats
# ============================================================
-keep class org.apache.commons.compress.compressors.FileNameUtil { *; }
-keep class org.apache.commons.compress.archivers.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
# Names kept for the same reason as the shell: root-package repackaging makes
# TypeDescription.<clinit> NPE (Package.getName on null) in GeckoRuntime.create.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# ============================================================
# Compose Runtime — consumer rules already exist; only suppress warnings
# ============================================================
-dontwarn androidx.compose.**

# ============================================================
# Material / AppCompat — consumer rules already exist; fallback only
# ============================================================
-dontwarn com.google.android.material.**

# ============================================================
# Custom Tabs (browser)
# ============================================================
-keep class androidx.browser.** { *; }
-dontwarn androidx.browser.**

# ============================================================
# Haze (background blur)
# ============================================================
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn dev.chrisbanes.haze.**

# Node.js JNI output bridge (R8 may rename onOutput otherwise)
-keep class com.sherwood.wta.core.nodejs.NodeBridge { *; }
-keep class com.sherwood.wta.core.nodejs.NodeJniOutputBridge {
    <init>(...);
    public void onOutput(java.lang.String, boolean);
}

# Firebase / FCM
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ============================================================
# Cronet (forced HTTP/3 upstream)
# ============================================================
# Cronet's native code reaches its Java classes via JNI and ServiceLoader
# (org.chromium.net.impl.** / org.chromium.base.**); R8 must not rename or
# strip them. The AAR ships consumer rules, this is the belt-and-braces copy.
-keep class org.chromium.net.** { *; }
-keep class org.chromium.base.** { *; }
-keep class org.chromium.components.** { *; }
-dontwarn org.chromium.**
-keepdirectories META-INF/services/**
