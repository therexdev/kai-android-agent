package com.kai.agent.voice;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Two bounded phrases: synthesize the next while the current one plays through one AudioTrack. */
final class BufferedLocalPlayer implements AutoCloseable {
    interface Output extends AutoCloseable {
        int write(byte[] data,int offset,int count);
        long head();
        void stop();
        void close();
    }
    interface OutputFactory { Output open(float speed, AudioAttributes attributes) throws Exception; }
    interface Metrics { void event(String name,long first,long second); }
    static final OutputFactory ANDROID_OUTPUT = (speed,attributes) -> {
        int size=Math.max(8192,AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build();
        try {
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Audio unavailable");
            track.setPlaybackParams(new PlaybackParams().setSpeed(Math.max(.5f,Math.min(2f,speed))).setPitch(1f));
            track.play();
        } catch(Exception error){track.release();throw error;}
        return new Output(){
            public int write(byte[] bytes,int offset,int count){return track.write(bytes,offset,count,AudioTrack.WRITE_BLOCKING);}
            public long head(){return Integer.toUnsignedLong(track.getPlaybackHeadPosition());}
            public void stop(){try{track.pause();track.flush();}catch(Exception ignored){}}
            public void close(){stop();track.release();}
        };
    };
    static final class Job {
        final String text,voice;final LocalSpeech.Callback callback;
        boolean requested,writing,played;int request,frames;ParcelFileDescriptor audio;String error;
        Job(String text,String voice,LocalSpeech.Callback callback){this.text=text;this.voice=voice;this.callback=callback;}
    }
    static final class Reply {
        final float speed;final AudioAttributes attributes;final ArrayDeque<Job> jobs=new ArrayDeque<>();
        boolean active=true,sealed,failed;Output output;long cursor,lastPlayedAt;
        Reply(float speed,AudioAttributes attributes){this.speed=speed;this.attributes=attributes;}
    }
    final Handler main;final NativeSpeechSource source;final OutputFactory factory;
    final Object lock=new Object();final ExecutorService player=Executors.newSingleThreadExecutor();
    Reply current;Job generating;boolean closed,warming;int warmEpoch,warmRequest;
    String warmingFamily;
    Metrics metrics=(name,first,second)->{};
    void setMetrics(Metrics metrics){this.metrics=metrics;}
    BufferedLocalPlayer(Context context,Handler main){this(main,new PocketSpeechSource(context.getApplicationContext(),main),ANDROID_OUTPUT);}
    BufferedLocalPlayer(Handler main,NativeSpeechSource source,OutputFactory factory){this.main=main;this.source=source;this.factory=factory;}

    void warm(String voice){
        LocalVoiceCatalog.Voice selected=LocalVoiceCatalog.find(voice);
        if(closed||selected==null||generating!=null)return;
        if(warming){
            if(selected.family.equals(warmingFamily))return;
            source.cancel(warmRequest);warming=false;warmRequest=0;warmEpoch++;
        }
        warming=true;warmingFamily=selected.family;int ticket=++warmEpoch;
        int id=source.warm(voice,new PocketClient.Callback(){
            public void ready(ParcelFileDescriptor audio,int frames,int rate,long elapsed){
                PocketClient.close(audio);if(closed||ticket!=warmEpoch)return;
                warming=false;warmRequest=0;pump();
            }
            public void error(String message){
                if(closed||ticket!=warmEpoch)return;
                // Warmup has no spoken text. A pending real request can open a fresh worker.
                warming=false;warmRequest=0;pump();
            }
        });
        if(warming&&ticket==warmEpoch)warmRequest=id;
    }
    void begin(String voice,float speed,AudioAttributes attributes){
        stop();if(closed)return;
        Reply reply=new Reply(speed,attributes);synchronized(lock){current=reply;}
        warm(voice);player.execute(()->run(reply));
    }
    void enqueue(String text,String voice,LocalSpeech.Callback callback){
        Reply reply=current;
        if(closed||reply==null||reply.sealed||reply.failed||!reply.active)return;
        synchronized(lock){
            if(reply.jobs.size()>=2)throw new IllegalStateException("Voice lookahead exceeds its limit");
            Job job=new Job(text,voice,callback);
            // Even a rejected prepared phrase must wait for the playing phrase's completion.
            if(!LocalSpeech.supportsText(text)){job.requested=true;job.error="This voice speaks English. Choose a device voice for this language.";}
            reply.jobs.addLast(job);lock.notifyAll();
        }
        pump();
    }
    void seal(){synchronized(lock){if(current!=null){current.sealed=true;lock.notifyAll();}}}
    void pump(){
        Reply reply=current;if(closed||warming||generating!=null||reply==null||!reply.active||reply.failed)return;
        Job next=null;
        synchronized(lock){for(Job job:reply.jobs){if(job.error!=null)break;if(!job.requested){next=job;job.requested=true;break;}}}
        if(next==null)return;
        Job job=next;generating=job;
        int id=source.synthesize(job.text,job.voice,new PocketClient.Callback(){
            public void ready(ParcelFileDescriptor audio,int frames,int rate,long elapsed){
                if(closed||current!=reply||!reply.active||generating!=job){PocketClient.close(audio);return;}
                generating=null;job.request=0;
                metrics.event("synthesis",elapsed,frames*1000L/24000);
                synchronized(lock){
                    if(audio==null||rate!=24000||frames<240||frames>24000*120){PocketClient.close(audio);job.error="The voice returned incomplete audio.";reply.failed=true;}
                    else{job.audio=audio;job.frames=frames;}
                    lock.notifyAll();
                }
                pump(); // Begin the following phrase now, before this one's playback completes.
            }
            public void error(String message){
                if(closed||current!=reply||!reply.active||generating!=job)return;
                generating=null;job.request=0;
                synchronized(lock){job.error=message;reply.failed=true;lock.notifyAll();}
            }
        });
        if(generating==job)job.request=id;
    }
    private void run(Reply reply){
        try{
            while(valid(reply)){
                Job job;
                synchronized(lock){
                    while(valid(reply)&&(job=nextForPlayback(reply))==null){
                        if(reply.sealed&&reply.jobs.isEmpty())return;
                        lock.wait();
                    }
                    if(!valid(reply))return;
                    job=nextForPlayback(reply);if(job==null)continue;
                    job.writing=true;
                }
                Job playing=job;
                if(job.error!=null){post(reply,()->jobFailure(reply,playing,playing.error));return;}
                if(reply.output==null){
                    Output output=factory.open(reply.speed,reply.attributes);
                    synchronized(lock){if(!valid(reply)){output.close();return;}reply.output=output;}
                }
                play(reply,job);
                if(!valid(reply))return;
                reply.lastPlayedAt=SystemClock.elapsedRealtime();
                synchronized(lock){job.played=true;}
                post(reply,()->{
                    synchronized(lock){if(reply.jobs.peekFirst()!=playing)return;reply.jobs.removeFirst();lock.notifyAll();}
                    playing.callback.finished();pump();
                });
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception error){
            post(reply,()->{Job head;synchronized(lock){head=reply.jobs.peekFirst();}if(head!=null)jobFailure(reply,head,"Local voice playback stopped. The reply remains available as text.");});
        }finally{
            synchronized(lock){if(reply.output!=null){reply.output.close();reply.output=null;}lock.notifyAll();}
        }
    }
    private Job nextForPlayback(Reply reply){
        for(Job job:reply.jobs)if(!job.played&&!job.writing)return job.audio!=null||job.error!=null?job:null;
        return null;
    }
    private void play(Reply reply,Job job)throws Exception{
        ParcelFileDescriptor descriptor;
        synchronized(lock){descriptor=job.audio;job.audio=null;}
        long start=reply.cursor,end=start+job.frames;boolean started=false;int total=0;
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
            byte[] buffer=new byte[8192];int count;
            while(valid(reply)&&(count=input.read(buffer))!=-1){
                int offset=0;
                while(offset<count&&valid(reply)){
                    int n=reply.output.write(buffer,offset,count-offset);if(n<=0)throw new IOException("Audio interrupted");
                    offset+=n;total+=n;if(total>job.frames*2)throw new IOException("Audio exceeds declared length");
                    if(!started&&reply.output.head()>start){started=true;playbackStarted(reply,job);}
                }
            }
            if(!valid(reply))return;
            if(total!=job.frames*2)throw new IOException("Incomplete audio");
            long deadline=SystemClock.elapsedRealtime()+(long)(job.frames*1000.0/24000/Math.max(.5f,reply.speed))+15000;
            while(valid(reply)&&reply.output.head()<end){
                if(!started&&reply.output.head()>start){started=true;playbackStarted(reply,job);}
                if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Audio stalled");
                Thread.sleep(5);
            }
            if(valid(reply)&&!started)playbackStarted(reply,job);
            reply.cursor=end;
        }
    }
    private void playbackStarted(Reply reply,Job job){
        long gap=reply.lastPlayedAt==0?-1:SystemClock.elapsedRealtime()-reply.lastPlayedAt;
        post(reply,()->{metrics.event("playback_start",0,0);if(gap>=0)metrics.event("playback_gap",gap,0);job.callback.started();});
    }
    private void jobFailure(Reply reply,Job job,String error){reply.failed=true;job.callback.failed(error);}
    private boolean valid(Reply reply){synchronized(lock){return !closed&&current==reply&&reply.active;}}
    private void post(Reply reply,Runnable event){main.post(()->{if(valid(reply))event.run();});}
    void stop(){
        long start=SystemClock.elapsedRealtime();
        Reply old;
        synchronized(lock){old=current;current=null;if(old!=null){old.active=false;
            for(Job job:old.jobs){PocketClient.close(job.audio);job.audio=null;}
            if(old.output!=null)old.output.stop();lock.notifyAll();}}
        if(generating!=null){source.cancel(generating.request);generating=null;warming=false;warmRequest=0;warmEpoch++;}
        if(old!=null)metrics.event("output_stop",SystemClock.elapsedRealtime()-start,0);
    }
    void release(){stop();warmEpoch++;warming=false;warmRequest=0;source.release();}
    @Override public void close(){release();closed=true;source.close();player.shutdownNow();}
}
