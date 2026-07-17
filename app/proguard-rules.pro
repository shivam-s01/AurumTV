# Aggressive shrink — every KB matters at our 6-8MB target.
-repackageclasses ''
-allowaccessmodification
-optimizations !code/simplification/arithmetic
-optimizationpasses 5

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
