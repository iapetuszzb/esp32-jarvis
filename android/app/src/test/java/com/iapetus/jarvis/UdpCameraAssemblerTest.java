package com.iapetus.jarvis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class UdpCameraAssemblerTest {
    private static final String TOKEN="udp-test-token-not-real-credential",SESSION="jarvis";
    private static final long WALL=1800000000000L;
    private byte[] jpeg(int size){byte[] j=new byte[size];j[0]=(byte)255;j[1]=(byte)216;j[size-2]=(byte)255;j[size-1]=(byte)217;return j;}
    private byte[] packet(byte[] image,int sequence,int index,long captured)throws Exception{
        int chunk=UdpCameraAssembler.CHUNK,offset=index*chunk,size=Math.min(chunk,image.length-offset);
        byte[] header=ByteBuffer.allocate(40).putInt(0x4a435531).put(new byte[12]).putInt(sequence)
            .putInt(image.length).putShort((short)index).putShort((short)((image.length+chunk-1)/chunk)).putLong(captured).array();
        byte[] key=MessageDigest.getInstance("SHA-256").digest(("jarvis-camera-udp-v1\n"+SESSION+"\n"+TOKEN).getBytes(StandardCharsets.UTF_8));
        byte[] nonce=Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(header),12);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(header);
        byte[] encrypted=c.doFinal(image,offset,size);return ByteBuffer.allocate(header.length+encrypted.length).put(header).put(encrypted).array();
    }
    @Test public void reordersFragmentsAndReleasesCompletedFrame()throws Exception{
        UdpCameraAssembler a=new UdpCameraAssembler(TOKEN,SESSION);byte[] image=jpeg(2500);
        for(int index:new int[]{2,0,0}){byte[] p=packet(image,1,index,WALL);assertNull(a.accept(p,p.length,WALL,100));}
        byte[] p=packet(image,1,1,WALL);UdpCameraAssembler.Frame f=a.accept(p,p.length,WALL,110);
        assertArrayEquals(image,f.jpeg);assertEquals(0,a.retainedBytes());
        assertNull(a.accept(p,p.length,WALL,120)); // completed sequence cannot be resurrected
    }
    @Test public void dropsPartialOldAndStaleFramesWithoutGrowingMemory()throws Exception{
        UdpCameraAssembler a=new UdpCameraAssembler(TOKEN,SESSION);byte[] image=jpeg(2500);
        byte[] p=packet(image,1,0,WALL);a.accept(p,p.length,WALL,100);assertEquals(image.length,a.retainedBytes());
        a.expire(1101);assertEquals(0,a.retainedBytes());assertNull(a.accept(p,p.length,WALL,1102));
        for(int sequence=2;sequence<1000;sequence++){
            p=packet(image,sequence,0,WALL);a.accept(p,p.length,WALL,1200);assertEquals(image.length,a.retainedBytes());
        }
        p=packet(image,1,1,WALL);assertNull(a.accept(p,p.length,WALL,1201));
        a.clear();p=packet(image,1000,0,WALL-5000);assertNull(a.accept(p,p.length,WALL,1202));assertEquals(0,a.retainedBytes());
    }
    @Test public void rejectsWrongKeyAndTamperedHeaderBeforeAllocating()throws Exception{
        byte[] p=packet(jpeg(2500),1,0,WALL);
        UdpCameraAssembler wrong=new UdpCameraAssembler(TOKEN+"wrong",SESSION);
        try{wrong.accept(p,p.length,WALL,100);fail("wrong key accepted");}catch(java.security.GeneralSecurityException expected){}
        assertEquals(0,wrong.retainedBytes());
        UdpCameraAssembler a=new UdpCameraAssembler(TOKEN,SESSION);p[39]^=1;
        try{a.accept(p,p.length,WALL,100);fail("tampered metadata accepted");}catch(java.security.GeneralSecurityException expected){}
        assertEquals(0,a.retainedBytes());
    }
    @Test public void pendingFrameExpiresEvenWithoutNewFrames(){
        UdpCameraAssembler.Frame f=new UdpCameraAssembler.Frame(jpeg(100),WALL,100);
        assertTrue(f.fresh(WALL+100,200));assertFalse(f.fresh(WALL+2100,2201));
    }
}
