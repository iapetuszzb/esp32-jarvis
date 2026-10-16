package com.iapetus.jarvis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JCU1: authenticated encrypted datagrams, latest frame only, no retransmit queue. */
final class UdpCameraAssembler {
    static final int PORT=19421, HEADER=40, CHUNK=1120, MAX_FRAME=256*1024;
    static final long PARTIAL_TTL_MS=1000, FRAME_TTL_MS=2000;
    static final class Frame {
        final byte[] jpeg;
        final long capturedAt, receivedAt;
        Frame(byte[] jpeg,long capturedAt,long receivedAt){this.jpeg=jpeg;this.capturedAt=capturedAt;this.receivedAt=receivedAt;}
        boolean fresh(long wall,long elapsed){return elapsed-receivedAt<=FRAME_TTL_MS&&wall-capturedAt<=4000&&capturedAt-wall<=5000;}
    }
    private final SecretKeySpec key;
    private byte[] boot,partial;
    private long newest=-1,started,captured;
    private boolean[] pieces;
    private int received;

    UdpCameraAssembler(String token,String session)throws Exception {
        key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(
            ("jarvis-camera-udp-v1\n"+session+"\n"+token).getBytes(StandardCharsets.UTF_8)),"AES");
    }
    synchronized void expire(long elapsed){if(partial!=null&&elapsed-started>PARTIAL_TTL_MS)clearPartial();}
    synchronized void clear(){clearPartial();boot=null;newest=-1;}
    private void clearPartial(){partial=null;pieces=null;received=0;}
    synchronized int retainedBytes(){return partial==null?0:partial.length;}

    synchronized Frame accept(byte[] packet,int length,long wall,long elapsed)throws Exception {
        expire(elapsed);
        if(length<HEADER+16||length>HEADER+CHUNK+16)return null;
        ByteBuffer b=ByteBuffer.wrap(packet,0,length);
        if(b.getInt()!=0x4a435531)return null;
        byte[] incomingBoot=new byte[12];b.get(incomingBoot);
        long sequence=Integer.toUnsignedLong(b.getInt());int total=b.getInt();
        int index=Short.toUnsignedInt(b.getShort()),count=Short.toUnsignedInt(b.getShort());
        long stamp=b.getLong();
        if(total<4||total>MAX_FRAME||count!=(total+CHUNK-1)/CHUNK||index>=count)return null;
        int size=Math.min(CHUNK,total-index*CHUNK);
        if(length!=HEADER+size+16||wall-stamp>4000||stamp-wall>5000)return null;
        byte[] header=Arrays.copyOf(packet,HEADER);
        byte[] nonce=Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(header),12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(header);
        byte[] data=cipher.doFinal(packet,HEADER,length-HEADER); // authenticate before allocating/replacing state
        if(!Arrays.equals(boot,incomingBoot)){
            // A newly booted camera can restart sequence. Capture time still limits replay lifetime.
            if(boot!=null&&stamp<=captured)return null;
            clearPartial();boot=incomingBoot;newest=-1;
        }
        if(sequence<newest)return null;
        if(sequence>newest){
            clearPartial();newest=sequence;started=elapsed;captured=stamp;
            partial=new byte[total];pieces=new boolean[count];
        }
        // Do not resurrect a completed or expired sequence, even if its remaining fragments arrive.
        if(partial==null||partial.length!=total||captured!=stamp||pieces[index])return null;
        System.arraycopy(data,0,partial,index*CHUNK,data.length);pieces[index]=true;received++;
        if(received!=count)return null;
        byte[] jpeg=partial;clearPartial();
        // Some camera drivers append padding after EOI; the server performs full JPEG decoding.
        if((jpeg[0]&255)!=255||(jpeg[1]&255)!=216)return null;
        return new Frame(jpeg,stamp,elapsed);
    }
}
