# Phone-managed research and Core compatibility — 0.4.11

The Android app now exposes `web_search` and `read_web_page` in its existing text/JSON agent protocol. Both the chat loop and saved-task engine execute these actions locally on the phone. No OpenAI built-in browsing model, KAI website session, desktop plugin, or Composio account is needed for public research. Search still requires internet access; this is not an offline search index.

## Retrieval

Default search uses DuckDuckGo HTML, then its Lite endpoint if the first request fails, is challenged, or has no usable results. Settings optionally accepts the owner's Brave Search API key; this uses the documented web search API first and falls back to the keyless routes. A personal Brave plan may charge for API requests. Public endpoints can change or block automation; neither route guarantees availability. Empty or failed retrieval is reported explicitly, never replaced with fabricated results or a narrow reference site presented as full web search.

Search results carry actual titles, URLs, excerpts, engine, fallback status and retrieval timestamps. Page reading strips active/navigation/form content, returns a bounded excerpt and marks truncation. Links are appended to replies and task outputs when the model omits them. Snippets are labeled as snippets. Dynamic, signed-in, binary and oversized pages may be unreadable.

The transport allows public HTTPS on port 443 only. It rejects credentials in URLs and private/reserved numeric addresses, checks every resolved address in the socket's DNS path, and validates each redirect. It has no cookies, account authentication, JavaScript or system proxy. Brave's key is attached only to the fixed search API origin/path, and a credential-bearing redirect is rejected. Responses are bounded to 2 MiB, with request and overall timeouts. Cancellation stops pending calls. Each research session permits three search attempts and three page reads; page URLs must come from the user's request or actual search results. Public search text remains untrusted data and cannot authorize a connected action.

The optional search key is stored through the existing encrypted settings path, redacted from debug representations and excluded from exports. Queries contain public topical keywords; API credentials are never sent to the inference model. Search providers receive search queries, and source sites receive their page requests. Selected excerpts are sent to the configured inference provider.

## Agent behavior

For explicit public research requests, a premature refusal or action proposal receives one bounded correction to use the phone search action. A repeated refusal produces a clear model-capability error. Failed research cannot prepare a research-dependent outgoing action, and a saved research step pauses instead of claiming completion without sources. Approved-action continuations can report receipts without unnecessarily repeating already completed research. Existing exact account/schema/argument reviews and UNKNOWN-outcome replay protection remain in force.

## Core requests

Core's standard catalog currently serves `llama31-8b` with a 4,096-token context. Android previously used character budgets as large as 28,000 for initial conversation context and 56,000 within a turn, while requesting up to 4,096 output tokens. This could exceed the served context even when the model family supports longer windows in other deployments.

Android now uses a conservative UTF-8 token estimate, reserves 512 tokens for server/template overhead, budgets output, and uses advertised `context_window`, `context_length` or `contextSize` metadata when present. Otherwise Koinos inference uses 4,096. Only whole older chat turns may be removed. The current request, system rules and current operation evidence stay pinned. Tool payloads may use only their explicitly supplied safe compact forms. Oversized pinned context fails locally with an actionable message; no saved history or memory is deleted. Retrieval includes fewer memory facts for Core prompts.

This estimate is not the model's exact tokenizer. Complex tasks may still require a larger served context or a more capable model. The UI classifies bounded provider errors into context/template/model issues without echoing raw server error text. The current Remote Access gateway may mask the detailed engine rejection; Android reports that limitation. No Core or Remote Access production service was changed by this Android update.

## Character motion

All four native characters keep their limbs and torso in the resting artwork pose in every activity, including greetings, speaking, working/searching and success. Gentle whole-character translation, head movement, gaze, blink, brows and speech mouth expressions remain. Floating moves feet and body together, rather than stretching a planted lower body. Reduced-motion and foreground/background scheduling remain supported.
