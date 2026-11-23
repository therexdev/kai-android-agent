# Formatted replies and readable speech · 0.4.9

Assistant replies in Character and Chat use native styled text for Markdown.
Bold, emphasis, headings, list items, code, strikethrough and link labels are
rendered without their source delimiters. Tables become readable text rows.
Plain email addresses and HTTP(S) URLs can also be opened by tapping them.
User-entered messages, approval arguments and stored/exported source text remain
literal and unchanged. Selecting a formatted reply copies its readable text.

Links use native Compose link annotations. Only validated HTTP, HTTPS and simple
mailto destinations receive a link action. Opening happens only after a user tap;
unsupported/executable schemes remain ordinary labels. Raw HTML is literal native
text, not evaluated HTML, and remote image Markdown displays only its alt text.
The character still uses its existing isolated local animated renderer.

Speech uses readable text before choosing the bounded TTS phrases. It omits
Markdown delimiters, list/header markers, code fences and hidden link destinations.
Link labels are spoken. Real content inside code, escaped punctuation, addresses
with underscores, numbers and arithmetic operators are preserved. Kiki's model,
voice embeddings, rate preference, AudioTrack player and synthesis engine are
unchanged. Both Kiki and installed Android voices use the same cleaned input.

Streaming speech waits for unresolved inline formatting or link syntax instead
of reading partial delimiters or URL fragments aloud. Complete formatted sentences
can start before the response ends. The original raw reply remains authoritative;
each spoken chunk maps its UTF-16 positions back to that source. Word-range starts
credit only their starting position, queued text is never credited, and completion
records the original source length only after all readable content finishes.
Interruption history and next-turn context therefore remain conservative.
Echo references contain only the recent spoken words, without hidden URLs.

The renderer/parser is CommonMark Java 0.32.0 with tables and strikethrough support.
Its BSD license is bundled in assets/licenses/COMMONMARK-LICENSE.txt. This version
includes the library's Android API 24+ compatibility fix; KAI still requires API 28+.
Original signing identity, encrypted data, Kiki defaults and foreground microphone
rules are preserved. No realtime cloud audio or new paid API call is introduced.

Executed checks and limits are recorded in qa/0.4.9/VALIDATION.md.
