# NOTLI Vocal Studio

Vocal Studio adds on-device music source separation to an individual song's three-dot menu.

## Runtime behavior

- Song audio is decoded locally by Android MediaCodec.
- The native Demucs Android engine receives float32 PCM through JNI.
- The engine separates the audio into drums, bass, other, vocals, guitar, and piano stems.
- NOTLI mixes the non-vocal stems into a temporary Instrumental WAV. The mix is peak-checked and globally attenuated only if the summed stems would clip.
- Preview uses the temporary result. Export is user-initiated through Android's document picker. Discard removes the temporary job directory.
- The source song is never modified.
- The engine and model are loaded only when the user opens Vocal Studio and starts a supported operation; existing playback and queue paths are not replaced.

## Network and privacy

The app does not use a NOTLI inference server, paid API, cloud processing, or subscription. The optional model download is fetched directly from a pinned Hugging Face revision using the device's own internet connection. A roughly 55 MB download may consume mobile data; the user must explicitly confirm the download. No audio file, PCM buffer, metadata, or generated stem is uploaded.

After model installation, inference works offline. The model is stored in app-private storage and verified against a pinned SHA-256 before it is used. The model is not bundled in the APK.

## Device support

The native engine is optional and is built for arm64-v8a with Android API 29 as its native deployment target. The app's existing minSdk remains unchanged. Vocal Studio is disabled on devices below Android 10 or without arm64-v8a; normal NOTLI playback remains available.

The first implementation uses one inference segment at a time to limit memory pressure. The upstream engine estimates about 0.6 GB per active inference segment. GPU/Vulkan is requested with CPU fallback in the engine. Actual speed, thermal load, and memory use vary by phone and audio length.

## Model license and commercial release gate

The Demucs Android engine source is MIT-licensed. The pretrained model weights are a separate asset and must not be assumed to inherit the engine's code license. The original Demucs maintainer stated that the pretrained weights are provided for scientific purposes only. The app therefore displays a research-only warning before the optional model download.

**The current htdemucs_6s model is not cleared for commercial use. Do not ship a commercial NOTLI distribution with this model enabled unless the necessary written rights have been obtained.** Before commercial release, replace the model and inference backend with a checkpoint whose weight license explicitly permits the intended commercial use, and complete a separate training-data/provenance review. Do not remove or weaken the warning as a substitute for a license.

A possible future research candidate is the Mel-Band RoFormer ONNX export at https://huggingface.co/musetric/vocal-separation-roformer-onnx. Its model card declares MIT and provides file hashes, but it is **not a drop-in replacement** for Demucs Android: it uses a different model architecture, requires a host-side STFT/iSTFT and chunking pipeline, has a large external tensor-data file, and says upstream training-data provenance is undocumented. It must undergo technical, size/performance, provenance, and license review before adoption.

## Pinned research model asset

- Model: Demucs v4 htdemucs_6s
- Source revision: 3c5ee475be622df764938de97e4281a7b07ffa58
- File: 5c90dfd2.safetensors
- Expected SHA-256: d2a1745f0744721f6b8ca5bf469b67c651ea5ed1b52998cab033b2158609d411
- Approximate download: 55 MB
- Usage status: research-only warning; not approved for commercial distribution

## Upstream engine

- https://github.com/jPaauwe/demucs-android
- Pinned commit: f4bc42b6c209beea2aa4a04d37c75cd06b0c90d2
- Upstream requires recursive submodule checkout for Eigen.
- The submodule is pinned in .gitmodules and the repository git tree.

## Build and verification

Clone with recursive submodules enabled, then build the app normally. CI checks out recursive submodules and configures the optional CMake JNI library.

Release readiness still requires:
- A passing Android CI build and lint/format checks.
- Physical-device tests on supported Android 10+ arm64 devices, including CPU-only fallback and Vulkan devices.
- Regression tests confirming playback, queue, playlists, library scan, and Android Auto are unchanged.
- Airplane-mode inference after the model is installed.
- Network inspection confirming no audio upload.
- Corrupt/partial model rejection and successful re-download.
- Long-file memory, thermal, cancellation, and cleanup tests.

## Current scope and known limitations

- The feature currently generates an instrumental mix, previews it, and supports opt-in WAV export or discard.
- Individual stem export and an interactive stem mixer are not yet included.
- Model download is explicit and cancellable by leaving the screen; resumable/range downloads are not yet implemented.
- Processing is tied to the Vocal Studio screen lifecycle; leaving the screen cancels the operation.
- Long inference can consume substantial RAM and battery. Only one segment is run concurrently by default.
- The native engine integration has not been confirmed on a physical device in this change. Do not describe the feature as release-ready until CI and device tests pass.
