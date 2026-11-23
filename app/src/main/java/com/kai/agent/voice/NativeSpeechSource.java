package com.kai.agent.voice;

import android.content.Context;
import android.os.Handler;
import android.os.ParcelFileDescriptor;

/** One serialized model worker; audio generation may overlap playback, never another generation. */
interface NativeSpeechSource extends AutoCloseable {
    int warm(String voice, PocketClient.Callback callback);
    int synthesize(String text, String voice, PocketClient.Callback callback);
    void cancel(int request);
    void release();
    void close();
}

final class PocketSpeechSource implements NativeSpeechSource {
    final PocketClient client;
    PocketSpeechSource(Context context, Handler main) { client = new PocketClient(context, main, 120000, 300000); }
    public int warm(String voice, PocketClient.Callback callback) { return client.warm(voice, callback); }
    public int synthesize(String text, String voice, PocketClient.Callback callback) { return client.request(text, false, "natural", 9, voice, callback); }
    public void cancel(int id) { client.cancel(id); }
    public void release() { client.close(); }
    public void close() { client.close(); }
}
