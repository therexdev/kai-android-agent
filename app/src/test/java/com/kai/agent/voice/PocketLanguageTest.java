package com.kai.agent.voice;

import org.junit.Test;
import static org.junit.Assert.*;

public class PocketLanguageTest {
    @Test public void acceptsEnglishPunctuationAndEmojiWithoutPlatformRegex() {
        assertTrue(LocalSpeech.supportsText("Hi. I’m KAI! Let's talk. 🤖"));
    }
    @Test public void rejectsUnsupportedScriptsIncludingSupplementaryHan() {
        for (String text : new String[]{"你好", "こんにちは", "안녕", "مرحبا", "Привет", "नमस्ते", new String(Character.toChars(0x20000))})
            assertFalse(text, LocalSpeech.supportsText(text));
    }
}
