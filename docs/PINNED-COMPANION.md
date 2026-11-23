# Fixed character panel · 0.4.7

In Character mode, the animated companion remains in a rounded panel below the
Character/Chat/Voice controls. Only the conversation text below it scrolls.
Streaming updates retain the current reading position and the full latest reply;
the previous 1,800-character display window is removed. Character reactions and
tap greetings remain active while the text scrolls. The panel adapts to the window
size while keeping space for readable text and the talk controls.

The general tagline, Open chat & transcript footer and microphone helper footer
are removed. The header Chat tab still opens the same conversation. Project context,
approval cards and the temporary-conversation notice remain available in the text
area. Start talking/End conversation and Your turn controls occupy one bottom row,
so the panel does not move when interruption becomes available.

Speech capture still starts only after the explicit talk action, and its lifecycle
rules are unchanged. The Kiki voice, synthesis/playback queue, encrypted storage,
agent execution and connection behavior are unchanged from 0.4.6.

See qa/0.4.7/VALIDATION.md for checks and limits. UI rendering fixtures do not prove
physical-phone audio quality or microphone behavior.
