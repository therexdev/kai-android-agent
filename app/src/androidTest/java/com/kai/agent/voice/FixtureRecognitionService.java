package com.kai.agent.voice;

import android.content.*;
import android.os.*;
import android.speech.*;
import java.util.ArrayList;
import java.util.Collections;

/** Scripted service in the test APK only; does not open a microphone or produce real recognition. */
public final class FixtureRecognitionService extends RecognitionService {
    public static final String COMMAND="com.kai.agent.test.RECOGNITION_COMMAND";
    public static final String STARTED="com.kai.agent.test.RECOGNITION_STARTED";
    private Callback current;private int sequence;
    private final BroadcastReceiver commands=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){
            if(current==null||intent.getIntExtra("sequence",-1)!=sequence)return;
            String command=intent.getStringExtra("command");
            Bundle text=new Bundle();text.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(Collections.singletonList(intent.getStringExtra("text"))));
            try{
                if("partial".equals(command))current.partialResults(text);
                else if("begin".equals(command))current.beginningOfSpeech();
                else if("end".equals(command))current.endOfSpeech();
                else if("final".equals(command)){Callback callback=current;current=null;callback.results(text);}
                else if("error".equals(command)){Callback callback=current;current=null;callback.error(intent.getIntExtra("error",SpeechRecognizer.ERROR_NO_MATCH));}
            }catch(RemoteException ignored){}
        }
    };
    // The pre-33 overload has no exported flag; this unprotected receiver exists only in the test APK.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(){super.onCreate();
        if(Build.VERSION.SDK_INT>=33)registerReceiver(commands,new IntentFilter(COMMAND),Context.RECEIVER_EXPORTED);
        else registerReceiver(commands,new IntentFilter(COMMAND));
    }
    @Override protected void onStartListening(Intent intent,Callback callback){
        current=callback;sequence++;
        try{callback.readyForSpeech(new Bundle());}catch(RemoteException ignored){}
        sendBroadcast(new Intent(STARTED).setPackage("com.kai.agent.preview").putExtra("sequence",sequence));
    }
    @Override protected void onCancel(Callback callback){if(current==callback)current=null;}
    @Override protected void onStopListening(Callback callback){try{callback.results(new Bundle());}catch(RemoteException ignored){}if(current==callback)current=null;}
    @Override public void onDestroy(){unregisterReceiver(commands);super.onDestroy();}
}
