# kai Android 0.4.21 validation

Based on the released 0.4.20 source archive (SHA-256 b27945bfa7f7e668488e28d308cbbf91fa7f1182d3ad3d5c3b666a2827ca169e).

- 365 JVM tests passed, no failures/errors/skips. Five new Robolectric/Compose UI tests cover a 320 x 320 dp viewport at 200% font scale, long details, fixed complete touch targets before/after scroll, detail taps, Close, Android Back, reopening, changed proposal identity with scroll reset and Compose saved-state restoration.
- Android lint: 0 errors, 24 warnings, 0 informational notices.
- ARM64 phone APK and Android instrumentation APK compiled. Original installed signing certificate and 16 KB alignment verified. Package com.kai.agent.preview, version code 28, version 0.4.21.
- No instrumentation test ran on an emulator or physical phone. This environment has no KVM device. The UI tests are local Android simulations, not a physical-phone test or live model/Google Docs action.
- First build setup attempt used a Java runtime without a compiler; validation was rerun with a full JDK 17. Test-harness compilation issues were fixed before execution. An initial synthetic Back-input test addressed the host activity instead of the dialog; the passing check invokes the actual open Android dialog's Back handler, without directly invoking an app callback. No physical-device Back-navigation check is claimed.

This change replaces the unbounded inline approval details with a bounded entry card and full-screen review. Only explicit Approve/Decline invokes existing decision handlers. Close/Back leaves the action pending. Tool routing, inference, diagnostics sanitizer, schema validation, database and execution/UNKNOWN handling are unchanged. The screenshot alone cannot establish why the user's Google Docs action failed or whether a tap registered approval; its own JSON report is still required.
