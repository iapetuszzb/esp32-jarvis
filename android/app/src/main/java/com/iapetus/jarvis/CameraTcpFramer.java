package com.iapetus.jarvis;

import java.io.IOException;

/** A bounded two-byte big-endian length prefix around unchanged JCU1 packets. */
final class CameraTcpFramer {
    interface Sink { void packet(byte[] data,int length)throws Exception; }
    private final byte[] packet=new byte[UdpCameraAssembler.HEADER+UdpCameraAssembler.CHUNK+16];
    private int prefixBytes,length,used;
    private long started;

    void expire(long now)throws IOException {
        if((prefixBytes!=0||used!=0)&&now-started>1000)throw new IOException("Camera TCP packet expired");
    }
    void accept(byte[] data,int count,long now,Sink sink)throws Exception {
        expire(now);
        for(int i=0;i<count;i++){
            if(prefixBytes<2){
                if(prefixBytes==0)started=now;
                length=(length<<8)|(data[i]&255);prefixBytes++;
                if(prefixBytes==2&&(length<UdpCameraAssembler.HEADER+16||length>packet.length))
                    throw new IOException("Invalid camera TCP packet length");
            }else{
                packet[used++]=data[i];
                if(used==length){
                    sink.packet(packet,length);
                    prefixBytes=length=used=0;
                }
            }
        }
    }
}
