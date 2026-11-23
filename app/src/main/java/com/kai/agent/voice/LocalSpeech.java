package com.kai.agent.voice;

import java.io.IOException;

/** Shared audio callbacks and language validation; no pitch effects or alternate native voices. */
final class LocalSpeech {
    interface Callback { void started(); void finished(); void failed(String message); }
    interface Sink { void accept(float[] pcm) throws IOException; }
    static boolean supportsText(String text) {
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (Character.UnicodeScript.of(codePoint)) {
                case HAN: case HANGUL: case HIRAGANA: case KATAKANA:
                case ARABIC: case CYRILLIC: case DEVANAGARI: return false;
                default: break;
            }
        }
        return true;
    }

}
