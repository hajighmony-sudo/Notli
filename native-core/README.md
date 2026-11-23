# Notli Rust Music Core

This crate starts the Rust-owned music domain. Its scope is deliberately limited to playback state transitions, queue ownership and invariants, track identity/source metadata, repeat/shuffle/navigation, backend action/event contracts, and direct audio-download task state.

Android remains the platform adapter for Media3 decoding/output, audio focus, MediaStore scanning/import, HTTP and file transfer, foreground-service policy, notifications, and Android media-session integration. These are OS-facing operations, not a second playback decision engine.

## Migration boundary

The existing Kotlin playback path is still active until a JNI bridge and Android adapter are implemented and verified. Do not remove the existing path in this foundation commit. Migration must transfer one responsibility at a time so only one authoritative queue/state owner exists.

## Validation

Run cargo test --manifest-path native-core/Cargo.toml and Android CI after JNI/Gradle integration is added. This foundation has not yet been built in repository CI.
