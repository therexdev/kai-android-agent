package com.kai.agent.voice;

import com.k2fsa.sherpa.onnx.*;
import java.io.*;

/** Bundled Kiki synthesis. No effects, speech API, or network fallback. */
final class LocalVoiceSynthesis implements AutoCloseable {
    final OfflineTts engine;
    final String family;
    LocalVoiceSynthesis(File root, String family) {
        this.family = family;
        File dir = new File(root, family);
        String data = new File(root, "shared/espeak-ng-data").getAbsolutePath();
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setNumThreads(2); model.setProvider("cpu"); model.setDebug(false);
        if (family.equals("kitten")) {
            OfflineTtsKittenModelConfig kitten = new OfflineTtsKittenModelConfig();
            kitten.setModel(path(dir, "model.int8.onnx")); kitten.setVoices(path(dir, "voices.bin"));
            kitten.setTokens(path(dir, "tokens.txt")); kitten.setDataDir(data);
            model.setKitten(kitten);
        } else throw new IllegalArgumentException("Unknown voice family");
        OfflineTtsConfig config = new OfflineTtsConfig(); config.setModel(model); config.setMaxNumSentences(1);
        engine = new OfflineTts(null, config);
        if (engine.sampleRate() != 24000) { engine.release(); throw new IllegalStateException("Invalid voice sample rate"); }
    }
    private static String path(File dir, String name) { return new File(dir, name).getAbsolutePath(); }
    void generate(String text, LocalVoiceCatalog.Voice voice, LocalSpeech.Sink sink) throws IOException {
        if (!family.equals(voice.family) || voice.speaker < 0 || voice.speaker >= engine.numSpeakers())
            throw new IOException("Requested voice is unavailable");
        final IOException[] failure = { null };
        final int[] frames = { 0 };
        engine.generateWithCallback(text, voice.speaker, 1f, new kotlin.jvm.functions.Function1<float[], Integer>() {
            public Integer invoke(float[] pcm) {
                try {
                    frames[0] += pcm.length;
                    if (frames[0] > 24000 * 120) throw new IOException("Speech exceeds limit");
                    for (float sample : pcm) if (!Float.isFinite(sample)) throw new IOException("Invalid speech sample");
                    sink.accept(pcm); return 1;
                } catch (IOException | RuntimeException error) { failure[0] = new IOException("Speech stopped", error); return 0; }
            }
        });
        if (failure[0] != null) throw failure[0];
        if (frames[0] < 240) throw new IOException("Voice returned no audio");
    }
    @Override public void close() { engine.release(); }
}
