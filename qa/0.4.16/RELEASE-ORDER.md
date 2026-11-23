# Context update release order

Prepared November 18, 2026. Core and Remote Access changes are not released.

1. Review and release KAI Core PR #13 (`bd1f2df87a86c90ecb8dd909373a831c1438798e`): https://github.com/therexdev/kai-core/pull/13. The earlier plan is automatic request sizing with an optional model cap under Models → Automatic context. A loaded engine must confirm its allocation; a configured maximum alone is not proof of available capacity.
2. Release the matching Remote Access change in PR #3 (`cb114d6aab67097a5bfed28f0710176740c42da7`): https://github.com/therexdev/kai-plugin-remote-access/pull/3. Complete the publisher's signed-package and catalog release procedure. The source change is version 0.3.4; no signed publisher package was produced here.
3. Install Android 0.4.16 over the existing app. Do not uninstall first. It discovers context automatically for new chats and task clients. Existing clients refresh after one minute; loading models in Settings also refreshes immediately.

After deployment, verify the selected model's authenticated `/v1/models` entry reports the intended `context_window` and `context_mode: "auto"`. Retest the user's literal Google document request with their connected account and verify both saved contents and returned link. Test a request needing more than 4K against an engine that supports the required allocation; retain receipts and check that an uncertain action is never replayed. None of these steps should change prepaid-network tariffs or routing.

The automated evidence uses simulated inference/service replies and an engine test fixture. Live GPU allocation, the hosted relay and the user's Google account require validation in the deployed environment.
