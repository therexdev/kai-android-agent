package com.kai.agent.voice;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** Non-exported, separately killable CPU engine. No account, chat, tools, or network initialization. */
public final class PocketVoiceService extends Service {
    static final int SAY=1,CHECK=2,RELEASE=3,AUDIO=4,READY=5,ERROR=6,WARM=7,WARMED=8;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Handler main=new Handler(Looper.getMainLooper());LocalVoiceSynthesis localEngine;volatile boolean busy;
    final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.sendingUid!=android.os.Process.myUid())return true;
        if(message.what==RELEASE){terminate();return true;}
        if(message.what==WARM&&message.replyTo!=null){
            String voice=message.getData().getString("voice","");
            if(!LocalVoiceCatalog.isLocalVoice(voice)||busy){reject(message.replyTo,message.arg1,busy?"busy":"voice");return true;}
            busy=true;Messenger destination=message.replyTo;int id=message.arg1;
            worker.execute(()->warm(destination,id,voice));return true;
        }
        if(message.what!=SAY&&message.what!=CHECK||message.replyTo==null)return true;
        Bundle args=message.getData();String text=args.getString("text","");String tone=args.getString("tone","cute");int pitch=args.getInt("pitch",9),id=message.arg1;
        String voiceId=args.getString("voice","");
        if(!valid(text,tone,pitch)||!LocalVoiceCatalog.isLocalVoice(voiceId)||busy){reject(message.replyTo,id,busy?"busy":!LocalVoiceCatalog.isLocalVoice(voiceId)?"voice":"text");return true;}
        busy=true;Messenger destination=message.replyTo;boolean check=message.what==CHECK;
        worker.execute(()->synthesize(destination,id,text,tone,pitch,voiceId,check));return true;
    }));
    static boolean valid(String text,String tone,int pitch){return !text.trim().isEmpty()&&text.length()<=320&&java.util.Arrays.asList("cute","natural","kai").contains(tone)&&pitch>=5&&pitch<=12;}
    @Override public IBinder onBind(Intent intent){return inbox.getBinder();}
    @Override public void onCreate(){super.onCreate();File[] stale=getCacheDir().listFiles((dir,name)->name.startsWith("kai-spoken-")&&name.endsWith(".pcm"));if(stale!=null)for(File file:stale)file.delete();}
    private void ensureEngine(String voiceId)throws Exception{
        if(!LocalVoiceCatalog.isLocalVoice(voiceId))throw new IOException("Voice unavailable");
        if(localEngine==null){
            File pack=LocalVoicePack.prepare(this,"kitten");
            localEngine=new LocalVoiceSynthesis(pack,"kitten");
            LocalVoicePack.removeRetiredPacks(this,pack);
        }
    }
    static void reject(Messenger destination,int id,String reason){
        Bundle error=new Bundle();error.putString("reason",reason);
        error.putString("error",reason.equals("busy")?"Kiki is still preparing speech. The reply remains available as text.":reason.equals("text")?"Kiki received an empty speech segment. The reply remains available as text.":"Choose Kiki or an installed device voice in Voice settings.");
        reply(destination,ERROR,id,error);
    }
    void warm(Messenger destination,int id,String voice){
        long start=SystemClock.elapsedRealtime();
        try{ensureEngine(voice);Bundle result=new Bundle();result.putLong("elapsed",SystemClock.elapsedRealtime()-start);busy=false;reply(destination,WARMED,id,result);}
        catch(Exception|LinkageError error){busy=false;reply(destination,ERROR,id,null);}
    }
    void synthesize(Messenger destination,int id,String text,String tone,int pitch,String voiceId,boolean check){
        File file=null;long start=SystemClock.elapsedRealtime();
        try{
            LocalVoiceCatalog.Voice voice=LocalVoiceCatalog.find(voiceId);
            ensureEngine(voiceId);
            // Unlinked after descriptor hand-off: generated replies are never kept as recordings.
            file=File.createTempFile("kai-spoken-",".pcm",getCacheDir());long[] frames={0};double[] energy={0};
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){
                LocalSpeech.Sink sink=pcm->{
                    byte[] bytes=new byte[pcm.length*2];for(int i=0;i<pcm.length;i++){
                        energy[0]+=(double)pcm[i]*pcm[i];int sample=Math.round(Math.max(-1,Math.min(1,pcm[i]))*32767);
                        bytes[i*2]=(byte)sample;bytes[i*2+1]=(byte)(sample>>8);
                    }frames[0]+=pcm.length;out.write(bytes);
                };
                localEngine.generate(text,voice,sink);
            }
            if(frames[0]<240||energy[0]<.0001)throw new IOException("Empty speech");
            Bundle result=new Bundle();result.putInt("frames",(int)frames[0]);result.putInt("rate",24000);result.putLong("elapsed",SystemClock.elapsedRealtime()-start);
            busy=false;
            if(check)reply(destination,READY,id,result);
            else try(ParcelFileDescriptor audio=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                result.putParcelable("audio",audio);reply(destination,AUDIO,id,result);
            }
        }catch(Exception|LinkageError e){busy=false;Bundle error=new Bundle();LocalVoiceCatalog.Voice selected=LocalVoiceCatalog.find(voiceId);
            error.putString("error",(selected==null?"Kiki":selected.name)+" could not generate speech on this device. Try another voice. The reply remains available as text.");reply(destination,ERROR,id,error);}
        finally{if(file!=null)file.delete();}
    }
    static void reply(Messenger destination,int what,int id,Bundle data){
        try{Message message=Message.obtain(null,what,id,0);if(data!=null)message.setData(data);destination.send(message);}catch(RemoteException ignored){}
    }
    void terminate(){android.os.Process.killProcess(android.os.Process.myPid());}
    @Override public boolean onUnbind(Intent intent){terminate();return false;}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
