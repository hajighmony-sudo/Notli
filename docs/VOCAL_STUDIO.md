# NOTLI Vocal Studio

Vocal Studio adds on-device music source separation to a song's three-dot menu.

## Runtime behavior

- Song audio is decoded locally by Android MediaCodec.
- The native Demucs Android engine receives float32 PCM through JNI.
- The engine separates the audio into drums, bass, other, vocals, guitar, and piano stems.
- NOTLI mixes the non-vocal stems into a temporary Instrumental WAV.
- Preview uses the temporary result. Export is user-initiated through Android's document picker. Discard removes the temporary job directory.
- The source song is never modified.

## Network and privacy

The app does not use a NOTLI inference server, paid API, or cloud processing. The optional model download is fetched directly from the pinned Hugging Face revision using the device's own internet connection. No audio file, PCM buffer, metadata, or generated stem is uploaded. After model installation, inference works offline.

The model is stored in app-private storage and verified against a pinned SHA-256 before inference. It is not bundled in the APK.

## Device support

The native engine is optional and is built for arm64-v8a with Android API 29 as its native deployment target. The app's existing minSdk and Rust ABI packaging are unchanged. Vocal Studio is disabled on devices below Android 10 or without arm64-v8a; normal NOTLI playback remains available.

The first implementation uses one inference segment at a time to limit memory pressure. The engine's upstream documentation estimates about 0.6 GB per active inference segment. GPU/Vulkan is requested with CPU fallback in the engine.

## Model license warning

The Demucs Android engine source repository is MIT-licensed. The model weights are a separate matter: the original Demucs maintainer stated that the pretrained weights are not covered by the code's MIT license and are provided only for scientific purposes. The model downloader therefore displays a research-only notice before downloading. Do not use these weights in a commercial product without obtaining separate rights. A future model with an explicit permissive weights license should replace this asset before broad commercial distribution.

## Pinned model asset

- Model: Demucs v4 htdemucs_6s
- Source revision: 3c5ee475be622df764938de97e4281a7b07ffa58
- File: 5c90dfd2.safetensors
- Expected SHA-256: d2a1745f0744721f6b8ca5bf469b67c651ea5ed1b52998cab033b2158609d411
- Approximate download: 55 MB

## Upstream engine

- https://github.com/jPaauwe/demucs-android
- Pinned commit: f4bc42b6c209beea2aa4a04d37c75cd06b0c90d2
- Upstream requires recursive submodule checkout for Eigen.

## Build

Clone with recursive submodules enabled, then build the app normally. The existing CI workflow already checks out recursive submodules and the app build now configures the optional CMake JNI library.

## Known limitations in this initial integration

- The feature has not been exercised on a physical Android device in this change.
- Android build/CI must pass before treating the integration as release-ready.
- The initial screen supports instrumental generation, preview, save, and discard. Direct export of individual stems and the mixer UI are follow-up enhancements.
- Long inference can consume substantial RAM and battery. Only one segment is run concurrently by default.