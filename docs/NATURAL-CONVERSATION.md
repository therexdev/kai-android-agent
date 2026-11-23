# Natural conversation · 0.4.8

Kiki and the pinned character panel remain. This update improves the existing
Android recognition -> selected text model -> local speech pipeline. It does not
enable a cloud realtime audio service or change the selected inference provider.

## Changes

- Natural pacing uses 650 ms after a clearly punctuated full thought, 850 ms for
  an ordinary longer phrase, 1100 ms for a very short fragment, and extra room
  after an unfinished phrase. Quick/Patient retain their selected pause lengths.
  This is a conservative text heuristic; recognition quality still matters.
- The commit deadline follows accepted user input. Empty, duplicate or echo-only
  endpoints cannot keep pushing it back. New speech can still join the pending turn.
- Recognition watchdogs reset during active dictation, rather than cutting it off
  60 seconds after listening began. A provider that ends speech but never sends a
  final result gets 2.5 seconds before the accepted partial is preserved and the
  recognizer is reopened. Late callbacks remain epoch-bound.
- Accepted interruption partials stop the reply without restarting the recognizer
  that is capturing the correction. They can also cancel a still-thinking model.
  Your turn remains the explicit immediate fallback. A capture failure or route
  loss preserves captured user text as an unsent Chat draft, not an automatic turn.
- Echo references exclude upcoming speech that has not started playing. Recent
  played text still guards against recognition of KAI's own voice. This is a text
  echo filter, not a new acoustic echo canceller: the Android recognizer owns the
  microphone and does not expose its AudioRecord session to this app.
- Speech chunk selection copies only its bounded phrase window. Display publication is coalesced to about 60 ms and flushed at stream end; speech
  and format validation still receive every token without waiting for another one.
  Exact reply validation and action approval are unchanged. Finished playback
  ledgers are released after their heard offsets are safely saved.
- Android 12+ offers System audio and available conversation audio routes in Voice.
  System audio keeps Android’s current output as the default, including media
  Bluetooth speakers. A headset/speaker communication route is an explicit choice.
  The communication route is confirmed before recognition starts, and cleared on
  stop/background/failure. Route loss stops the session rather than silently
  switching a live conversation to the speaker. End voice before changing the
  route. Older Android versions continue using system routing. The recognizer
  service ultimately controls which microphone it supports.

## A longer test on the phone

In Voice, scroll to Long conversation test and tap Start test. This collects
bounded numeric timing/error events in memory; it does not start the microphone.
Then start talking normally. After testing, return to Voice and Save test report.
The Android document picker lets you choose where to save KAI-voice-test.json.
No report uploads automatically. Clear test discards the in-memory report.

Use the same phone and model for a 15–20 minute session. Include a short exchange,
a long multi-paragraph reply, a spoken correction during playback, Your turn,
and a thought lasting over one minute with pauses. Try speaker, wired audio and
Bluetooth in separate sessions; confirm both the output and the actual microphone
by moving the phone away from the headset. Test a Bluetooth disconnect, a phone
call/focus loss, background/foreground and a clean restart. Do not use a real
outgoing tool action as the test request.

The report records recognition starts/errors/watchdogs, committed turn lengths,
interruption counts, synthesis duration versus generated audio duration, observed
playback-start/gap timing, output stop duration and communication route type. It
contains no audio, conversation text, device names/addresses, keys or account IDs.
It keeps at most 1200 recent events plus aggregate counts. Playback timings are
app observations, not acoustic measurements at a Bluetooth earbud.

If gaps grow while synthesis takes longer than the generated audio duration,
local voice throughput/thermal load is a likely contributor. Recognizer errors
or watchdogs point to capture/provider recovery. A real-phone run is still needed
to establish microphone routing, echo quality, heat, battery and sustained latency.

## Optional realtime audio

A separate opt-in mode could use a persistent WebRTC speech-to-speech connection
to a cloud realtime model, replacing the separate recognition/text/local-synthesis
chain for conversation audio. It can stream replies and cancel/truncate unheard
audio on interruption. Semantic turn detection can use the content of speech to
wait longer for an unfinished thought and respond sooner to a completed one.

That mode would send microphone audio to the provider and require network access,
compatible API access and usage billing. It would use a realtime provider voice,
not the local Kiki model. Kiki would remain available in the existing mode. It is
not enabled or implemented in this release, and is not required for this update.

Any future integration must keep phone-owned memory, exact tool approvals,
explicit foreground microphone control and conservative unheard-output handling.
It needs an owned capture/playback path for meaningful acoustic echo handling,
plus route/lifecycle tests. A cloud voice model alone does not prove Bluetooth
quality or remove the need for physical-phone tests.

Official references reviewed November 17, 2026:
- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager
- https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
- https://developers.openai.com/api/docs/guides/realtime-webrtc
- https://developers.openai.com/api/docs/guides/realtime-vad
- https://developers.openai.com/api/docs/guides/realtime-conversations
