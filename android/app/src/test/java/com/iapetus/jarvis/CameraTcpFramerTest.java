package com.iapetus.jarvis;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraTcpFramerTest {
    @Test public void handlesSplitLengthsAndCoalescedPackets()throws Exception {
        byte[] a=new byte[1176],b=new byte[56];a[0]=42;b[0]=17;
        byte[] wire=ByteBuffer.allocate(a.length+b.length+4).putShort((short)a.length).put(a).putShort((short)b.length).put(b).array();
        CameraTcpFramer f=new CameraTcpFramer();ArrayList<byte[]> packets=new ArrayList<>();
        CameraTcpFramer.Sink sink=(p,n)->packets.add(Arrays.copyOf(p,n));
        f.accept(Arrays.copyOfRange(wire,0,1),1,100,sink);
        f.accept(Arrays.copyOfRange(wire,1,500),499,101,sink);
        f.accept(Arrays.copyOfRange(wire,500,wire.length),wire.length-500,102,sink);
        assertEquals(2,packets.size());assertArrayEquals(a,packets.get(0));assertArrayEquals(b,packets.get(1));
        f.expire(10000); // completed packet retains no pending stream bytes
    }
    @Test public void rejectsOversizedLengthWithoutAllocatingFrame()throws Exception {
        for(int length:new int[]{0,55,1177,65535}){
            CameraTcpFramer f=new CameraTcpFramer();
            try{f.accept(ByteBuffer.allocate(2).putShort((short)length).array(),2,100,(p,n)->fail());fail();}
            catch(IOException expected){}
        }
    }
    @Test public void incompleteStreamPacketHasAbsoluteExpiry()throws Exception {
        CameraTcpFramer f=new CameraTcpFramer();
        f.accept(new byte[]{0,56,1},3,100,(p,n)->fail());
        f.accept(new byte[]{2},1,1000,(p,n)->fail()); // later bytes do not extend deadline
        try{f.expire(1101);fail();}catch(IOException expected){}
    }
}
