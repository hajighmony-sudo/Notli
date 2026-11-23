# Notli Rust Music Core

This crate is the Rust-owned domain engine for Notli music playback. It owns playback state transitions, queue invariants, repeat/shuffle/navigation, typed backend events and download-task state.

## Android boundary

Kotlin is the Android adapter and UI language. New UI screens should use Jetpack Compose. Android-specific operations remain in Kotlin: Media3 audio decoding/output, MediaStore and file access, HTTP transfer, audio focus, foreground service, MediaSession and notifications. Rust decides which track should play; Media3 performs audio output.

## JNI bridge

- `jni_bridge.rs` exposes a handle-based JNI API.
- `RustMusicCore.kt` loads `libnotli_music_core.so` and forwards JSON-encoded typed commands/events.
- `RustMusicEngine` owns and closes one native handle.
- Playback commands, backend events, player snapshots and download commands use Serde JSON.
- JNI responses are JSON objects with either `ok: true` and `value`, or `ok: false` and `error`.
- `nativeRestore` replaces the player from a persisted `PlayerSnapshot`. Snapshots carry a schema `version`; unknown versions are rejected and an invalid snapshot never replaces live state.
- Rust panics are caught at the JNI boundary and reported as `ok: false` errors instead of aborting the process.
- Limits: `MAX_QUEUE_LEN` (100,000 queue entries) and `MAX_DOWNLOAD_TASKS` (1,000 tracked downloads; finished ones can be removed with `Remove` / `nativeDownloadClearFinished`).

The JNI bridge is transport infrastructure. It is not yet wired into the live ExoPlaybackStateHolder; the existing Kotlin playback path remains active until the adapter migration and device tests pass.

## Build

Install Rust stable, the Android NDK version configured in the app Gradle file, and cargo-ndk:

```bash
cargo install cargo-ndk --locked
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo test --manifest-path native-core/Cargo.toml
./gradlew app:packageDebug
```

Gradle builds native shared libraries for arm64-v8a, armeabi-v7a, x86_64 and x86 before Android packaging.

## Migration gates

Do not remove the Kotlin playback implementation until the Rust adapter has parity for all current queue operations, Media3 callbacks, persistence recovery, audio focus, background playback, external controls and error handling. Only one queue/state owner may be active at a time.
