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
