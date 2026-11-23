> Superseded motion behavior in 0.4.11: hand waves and all limb/torso gestures are removed. Whole-character floating and head/face animation remain. See [current behavior](WEB-RESEARCH-AND-CORE.md). The details below describe the historical 0.4.10 implementation.

# Connected characters · 0.4.10

All four characters keep their existing artwork and original faces. A connected
64 × 80 bitmap mesh deforms the body texture with seven weighted bones. The
head blends continuously into the neck; no moving horizontal head/chest cut is
exposed. Each character has shoulder, elbow and wrist anchors placed for its
proportions. KAI's cape stays behind its body rather than stretching with an arm.
The original raster source files are unchanged. Derived transparent lossless WebP
skins and face layers are produced by `tools/build_native_character_art.py`.

A tap starts a 2.85-second greeting: short anticipation, eased arm lift and elbow
bend, a held raised arm with wrist waving, and an eased return to rest. Spark is
lively, Nova is curious, Sage uses a smaller wing gesture, and KAI bends its
mechanical arm. Listening adds an attentive tilt; thinking looks upward; audible
speech adds small conversational beats. State changes blend from the current
pose. Blinking and a short attack/release mouth animation follow audible speech.
These are expressive 2D gestures, not phoneme or finger animation.

`NativeCharacterMotion` calculates the connected deformation. `NativeCharacterView`
uses Android Canvas.drawBitmapMesh and cached bitmap face layers. There is no
WebView, JavaScript bridge, browser/GPU readback, network or remote asset request
in the runtime renderer. Mesh coordinates and weights are prepared once off the
UI thread; drawing reuses the vertex array. The frame callback is capped at
approximately 30 updates per second. This is a work bound, not measured phone FPS.
Reduced motion uses a still expression. Backgrounding and disposal remove frame
callbacks. Bundled poster artwork remains visible while native assets load.

The older SVG/CSS and motion.js assets remain as art/reference inputs and for the
deterministic preview tooling; the app does not execute their browser renderer.
Kiki, recognition, synthesis, memory, external actions and credentials are unchanged.
The 0.4.9 Markdown renderer and pinned transcript remain. Executed checks and
physical-phone testing limits are in `qa/0.4.10/VALIDATION.md`.
