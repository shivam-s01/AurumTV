# Aggressive shrink — every KB matters at our 6-8MB target.
#
# NOTE: -repackageclasses '' + -allowaccessmodification is a known
# combination that can corrupt Compose's runtime touch/gesture dispatch
# in release builds specifically — debug builds (no minification) work
# fine, release builds go silently unresponsive to taps with no crash,
# because R8 repackages/renames internal Compose runtime classes that
# gesture detection can rely on resolving reflectively. Removing
# -repackageclasses and adding explicit Compose keep rules below fixes
# this while still shrinking everything else.
-allowaccessmodification
-optimizations !code/simplification/arithmetic
-optimizationpasses 5

# ── Jetpack Compose runtime — required for release-build touch/gesture
# handling to work at all; without these R8 can rename/strip classes
# that Compose's pointer input system locates reflectively. ───────────
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.tv.material3.** { *; }
-dontwarn androidx.compose.**

# Media3 / ExoPlayer needs its own service + session classes kept
-keep class androidx.media3.session.MediaSessionService { *; }
-keep class com.aurum.musictv.player.AurumTvPlaybackService { *; }

# Keep data model fields (we parse JSON manually via org.json, so field
# names matter if you ever switch to reflection-based parsing later)
-keep class com.aurum.musictv.data.model.** { *; }

-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**

# ── Ktor / Supabase-kt ──────────────────────────────────────────────────
# Ktor's OkHttp engine references classes (SocketTimeoutException,
# HttpTimeoutCapabilityConfiguration, etc.) via reflection/optional
# codepaths that R8 can't statically prove are unused, so it flags them
# as "missing" during release shrinking even though they're fine at
# runtime. -dontwarn silences the R8 analysis for these instead of
# needing every transitive Ktor class enumerated by hand.
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class com.aurum.musictv.**$$serializer { *; }
-keepclassmembers class com.aurum.musictv.** {
    *** Companion;
}
-keepclasseswithmembers class com.aurum.musictv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── NewPipeExtractor + NewValve (native YouTube stream resolution) ──────
# Same rules as the phone app: the extractor parses live YouTube responses
# with reflection-heavy JSON/HTML code (nanojson, jsoup, protobuf-lite,
# Rhino for signature deciphering); stripping/renaming "unused" members
# breaks extraction only in RELEASE builds, with no compile-time warning.
-keep class org.schabi.newpipe.** { *; }
-keep class com.github.shalva97.** { *; }
-keep class io.github.shalva97.** { *; }
-dontwarn org.schabi.newpipe.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn com.google.protobuf.**
-keep class com.google.protobuf.** { *; }

# OkHttp (used by NewValve's Downloader and by AurumApi)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
