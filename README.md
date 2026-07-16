# Aurum TV — Native Android TV App

Lightweight native Kotlin Android TV companion to the Aurum Flutter phone
app. Built separately (no Flutter engine) specifically to hit a **6-8MB
APK** and run smoothly on **1GB RAM / 8GB storage** TV boxes.

## Why a separate native project (not Flutter)

Flutter's engine alone adds ~5-7MB to any release APK before a single
feature is added. That budget doesn't leave room for a 6-8MB total target.
This project uses classic **Leanback** (not Compose-TV, which is heavier)
for D-pad-native UI, and Media3/ExoPlayer for playback — the same engine
family as `AurumAudioEngine.kt` in the phone app, just wired through
Media3's built-in `MediaSessionService` instead of a custom MethodChannel
bridge (there's no Flutter side to bridge to here).

## What's reused from the phone app

- **Cloudflare Worker backend** — same URL, same endpoints
  (`/api/search/songs`, `/api/yt-proxy`, `/api/yt-stream`, `/api/songs`).
  Zero backend changes needed.
- **Buffer-tuning philosophy** from `AurumAudioEngine.kt`'s low-RAM fixes,
  adapted with an even smaller footprint (2MB buffer ceiling vs phone's
  larger budget) since TV boxes have less RAM to spare.
- **Brand colors** from `aurum_theme.dart` (dark palette only — TV is
  always dark-room viewing, so no light-theme toggle here).
- **Launcher icon** copied directly from the phone app's mipmap.

## What's intentionally NOT included (yet)

To hit the size/RAM target, this first version drops: premium/paywall,
Shorts, offline downloads, lyrics, recommendation engine, local file
playback, 16-language localization (English only for now), and the
custom spring-physics animations from the phone UI (Leanback's built-in
focus scale animation is used instead — it's what TV users expect anyway).

## Project structure

```
app/src/main/java/com/aurum/musictv/
  data/            Song model + AurumApi (OkHttp + org.json, no Retrofit/Gson)
  player/          AurumTvPlaybackService (Media3 MediaSessionService)
  ui/home/         Leanback BrowseSupportFragment + card presenter
  ui/player/       Now-playing screen + ViewModel (MediaController bridge)
  ui/search/       Leanback SearchSupportFragment (D-pad + voice search free)
```

## First-time setup

This zip has `gradle/wrapper/gradle-wrapper.properties` but not the
wrapper jar/script (binary file, can't be generated without network
access on my end). Generate it once, from a machine with Gradle
installed (or via GitHub Actions — easiest path since your CI already
has Gradle available):

```bash
gradle wrapper --gradle-version 8.7
```

If you'd rather skip this locally: just push the project as-is to GitHub
and let the Actions workflow below run `gradle wrapper` as its first
step, or use `gradle assembleRelease` directly in CI without a wrapper
at all.

Also copy `local.properties.example` → `local.properties` and point
`sdk.dir` at your actual SDK path.

## Building (from Termux, same workflow as the phone app)

```bash
# one-time: make sure you have a JDK 17 + Android SDK cmdline-tools
# available in Termux (same setup you use for the Flutter app's android/
# folder builds, if you've done manual Gradle builds before)

cd ~/AurumTV
./gradlew assembleRelease

# output APK:
# app/build/outputs/apk/arm64-v8a/release/app-arm64-v8a-release-unsigned.apk
```

Since you build via GitHub Actions for the phone app, the simplest path is
to push this as a **second repo** (or a subfolder with its own workflow)
and let Actions build + sign it, same pattern as your existing
`deploy.sh` → Actions → GitHub Releases flow. A sample workflow:

```yaml
name: Build Aurum TV
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - run: gradle wrapper --gradle-version 8.7
      - run: chmod +x gradlew && ./gradlew assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: aurum-tv-apk
          path: app/build/outputs/apk/**/*.apk
```

(Signing config / keystore secrets same pattern as your phone app's
`KEYSTORE_BASE64` GitHub Secret — add that step once this base build is
confirmed working.)

## Checking actual APK size

After `assembleRelease`, always verify with:
```bash
unzip -l app/build/outputs/apk/arm64-v8a/release/*.apk | tail -1
```
This is release + minify + shrink + single-ABI split, which is what gets
you close to the 6-8MB target — a debug build will be much bigger and
isn't representative.

## Immediate next steps (in priority order)

1. **Build once on your actual TV box** (or an emulator with 1GB RAM
   profile) before adding anything else — confirm playback is smooth and
   check real APK size against target.
2. If over budget: check whether Leanback's transitive deps pulled in
   anything unexpected (`./gradlew app:dependencies` to inspect), and
   confirm `isMinifyEnabled`/`isShrinkResources` actually ran (check the
   R8 mapping file exists in `build/outputs/mapping/release/`).
3. Wire up play/pause/seek D-pad key handling in `PlayerActivity` (center
   button = play/pause, left/right = seek — currently only autoplay-on-open
   is implemented).
4. Add a "Now Playing" mini-indicator back on the Home row screen once
   core playback is stable.
5. Only after 1-4 are solid: consider adding Library/Playlists as a third
   Leanback row source, reusing the same `Song` model and `AurumApi`
   pattern already established here.
