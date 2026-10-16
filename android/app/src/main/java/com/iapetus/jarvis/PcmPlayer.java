package com.iapetus.jarvis;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.LinkedBlockingQueue;

final class PcmPlayer implements AutoCloseable {
    private final LinkedBlockingQueue<byte[]> queue=new LinkedBlockingQueue<>(6);
    private final AudioTrack track;
    private final Thread thread;
    private volatile boolean running=true;
    PcmPlayer() {
        int size=Math.max(AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT),48000);
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        thread=new Thread(()->{
            try {
                while(running){byte[] pcm=queue.take();int offset=0;
                    while(running&&offset<pcm.length){int n=track.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_BLOCKING);if(n<=0)break;offset+=n;}}
            }catch(InterruptedException ignored){}
        },"jarvis-playback");thread.start();
    }
    boolean offer(byte[] pcm){return pcm.length==0 || queue.offer(pcm);}
    void flush(){queue.clear();try{track.pause();track.flush();track.play();}catch(IllegalStateException ignored){}}
    @Override public void close(){running=false;queue.clear();thread.interrupt();try{track.pause();track.flush();track.stop();}catch(Exception ignored){}
        try{thread.join(1500);}catch(InterruptedException ignored){}track.release();}
}
