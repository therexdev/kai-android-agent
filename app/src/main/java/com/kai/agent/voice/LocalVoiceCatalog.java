package com.kai.agent.voice;

import java.util.*;
import com.kai.agent.data.KikiVoiceDefaults;

/** The only bundled speech identity. Device TTS voices remain available separately. */
public final class LocalVoiceCatalog {
    public static final String KIKI = KikiVoiceDefaults.VOICE_ID;
    public static final class Voice {
        public final String id, family, name, detail;
        public final int speaker;
        Voice() { id=KIKI;family="kitten";name="Kiki";detail="Default voice · English · On device";speaker=7; }
    }
    public static final List<Voice> VOICES = Collections.singletonList(new Voice());
    public static Voice find(String id) { return KIKI.equals(id) ? VOICES.get(0) : null; }
    public static boolean isLocalVoice(String id) { return find(id) != null; }
    private LocalVoiceCatalog() {}
}
