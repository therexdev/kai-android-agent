package com.kai.agent.voice;

import android.content.Intent;
import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Test APK only. Java keeps this separate engine process independent of target-app Kotlin libraries. */
public final class FixtureTtsService extends TextToSpeechService {
    public static final String ACTION="com.kai.agent.test.SYNTHESIS_041";
    private volatile boolean stopped;
    @Override protected int onIsLanguageAvailable(String lang,String country,String variant) {
        return "en".equals(lang)||"eng".equals(lang)?TextToSpeech.LANG_COUNTRY_AVAILABLE:TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String lang,String country,String variant){return onIsLanguageAvailable(lang,country,variant);}
    @Override protected String[] onGetLanguage(){return new String[]{"eng","USA",""};}
    @Override public String onGetDefaultVoiceNameFor(String lang,String country,String variant){return "fixture-fast";}
    @Override public int onIsValidVoiceName(String name){return Arrays.asList("fixture-fast","fixture-soft","fixture-online").contains(name)?TextToSpeech.SUCCESS:TextToSpeech.ERROR;}
    @Override public int onLoadVoice(String name){return onIsValidVoiceName(name);}
    @Override public List<Voice> onGetVoices(){return Arrays.asList(new Voice("fixture-fast",Locale.US,Voice.QUALITY_HIGH,Voice.LATENCY_LOW,false,Collections.emptySet()), new Voice("fixture-soft",Locale.US,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,Collections.emptySet()), new Voice("fixture-online",Locale.US,Voice.QUALITY_VERY_HIGH,Voice.LATENCY_LOW,true,Collections.emptySet()));}
    @Override protected void onStop(){stopped=true;}
    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback){
        stopped=false;
        String text=request.getCharSequenceText().toString();
        sendBroadcast(new Intent(ACTION).setPackage("com.kai.agent.preview").putExtra("text",text)
            .putExtra("rate",request.getSpeechRate()).putExtra("pitch",request.getPitch()).putExtra("voice",request.getVoiceName()));
        if(text.contains("FAIL")){callback.error(TextToSpeech.ERROR_SYNTHESIS);callback.done();return;}
        callback.start(16000,AudioFormat.ENCODING_PCM_16BIT,1);
        callback.rangeStart(0,0,text.length());
        byte[] pcm=new byte[16000*2*2]; // Two seconds of quiet PCM, not a speech-quality model.
        int offset=0;
        while(offset<pcm.length&&!stopped){
            int length=Math.min(callback.getMaxBufferSize(),pcm.length-offset);
            if(callback.audioAvailable(pcm,offset,length)!=TextToSpeech.SUCCESS)break;
            offset+=length;
        }
        callback.done();
    }
}
