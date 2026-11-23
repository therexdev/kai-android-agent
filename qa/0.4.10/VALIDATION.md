# KAI Android 0.4.10 alpha

The four original characters now use a connected native 2D body mesh. Smooth
head/neck weights replace the moving horizontal head cut. A greeting raises the
arm, bends the elbow, waves the wrist and eases back to rest. Listening, thinking
and audible speaking have restrained head/eye/body gestures. Mouth motion follows
audible playback; these are expressive gestures, not phoneme or finger animation.
State changes blend from the current pose. Original raster sources are unchanged;
derived lossless transparent sprites retain the original artwork.

The released renderer uses Android Canvas.drawBitmapMesh and cached face layers.
It does not use a WebView, browser shader, GPU readback or remote assets. Mesh
weights are prepared off the UI thread and vertices reuse a buffer. Callbacks are
capped near 30 updates per second, suspended in the background and removed on
disposal. Reduced motion uses a still expression without a continuous frame loop.
A bundled poster displays during asset loading.

Completed checks:
- 270 JVM tests passed, zero failures/errors/skips, including eight native motion
  tests covering normalized weights, neutral geometry, raised paw and elbow,
  independent wrist movement, eased return, continuous neck weights, undisturbed
  feet/face and playback-gated speaking gestures. Eight reference Node checks
  also passed. Android lint: zero errors, 22 warnings.
- App, instrumentation and ARM64 builds passed. Six Android tests passed on an
  API 28 x86_64 software emulator: four native renderer tests, a pinned-panel
  scrolling/streaming test and an encrypted-state in-place update test.
- Taps exercised all four native skins. Recorded poses verify raised shoulders,
  bent elbows and wrist variation. Final screenshots in device-native-final and
  pinned-native-final were visually reviewed. Full Spark body color samples are
  required, including after frame callbacks stop. A separate software Bitmap
  Canvas test also requires a complete body. This guards against the earlier
  blank body seen with the abandoned browser renderer.
- Mouth opening/closing follows actual playback activity. Reduced motion and
  background state stop frame counters. Resuming keeps the same renderer. Native
  scrolling and streamed text retain the pinned companion panel. The pinned
  layout check uses reduced motion for deterministic Compose synchronization;
  the four live renderer tests use the normal Android frame clock.
- Early WebView experiments failed visual checks despite correct offscreen
  pixels. They were replaced by the native renderer. The exploratory WebView
  flag file was removed before all final native tests. Initial diagnostics are
  retained as failed experiments, not counted as release validation. An initial
  native run encountered a System UI ANR on the software emulator. The final
  64 × 80 mesh reduces drawing work, and live tests observe actual frames without
  waiting for Compose animation idleness. Initial failure/recovery evidence is
  under diagnostics-native. Final screenshots were checked for system dialogs.
- The signed in-place update preserved the existing encrypted fixture, Kiki
  defaults, deliberate device voice selections, saved rate, exact connection
  ownership and conversation data. All production Kotlin outside CharacterRig,
  NativeCharacterMotion and NativeCharacterView matches 0.4.9. Markdown rendering,
  speech, recognition, data and external-action implementation remain unchanged.
- The ARM64 installer matches 851 relevant tested universal APK entries.
  Seven native libraries, Kiki model/embeddings/tokens and memory assets remain
  unchanged; memory ONNX remains stored for mmap. Retired voices remain absent.
- Package com.kai.agent.preview, versionCode 17, original signer SHA-256:
  a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: this is improved 2D skinning, not new 3D models or individual fingers.
Physical-phone frame rate, battery, sustained speech latency, Bluetooth and thermal
behavior were not measured. The small MP4 is a deterministic design rendering of
the same rig math, not phone footage or a performance benchmark. No real
microphone, live account, paid inference or external action was used in these
checks. Install over the existing alpha; do not uninstall and erase its data.
