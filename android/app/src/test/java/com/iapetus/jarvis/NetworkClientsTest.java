package com.iapetus.jarvis;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NetworkClientsTest {
    private static Callback discard(CountDownLatch done) {
        return new Callback(){
            public void onFailure(Call c,IOException e){done.countDown();}
            public void onResponse(Call c,Response r)throws IOException{try(Response response=r){response.body().string();}finally{done.countDown();}}
        };
    }
    @Test public void stalledStatusDoesNotQueueChatAndDuplicatePollsAreSkipped() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            CountDownLatch statusReceived=new CountDownLatch(1),releaseStatus=new CountDownLatch(1);
            AtomicInteger polls=new AtomicInteger();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){
                public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
                    if(request.getPath().equals("/status")){
                        polls.incrementAndGet();statusReceived.countDown();
                        releaseStatus.await(8,TimeUnit.SECONDS);
                    }
                    return new MockResponse().setBody("ok");
                }
            });
            server.start();
            OkHttpClient status=NetworkClients.status(),chat=NetworkClients.requests();
            // A single slot makes starvation reproducible if the dispatchers are accidentally shared.
            status.dispatcher().setMaxRequestsPerHost(1);chat.dispatcher().setMaxRequestsPerHost(1);
            StatusPoller poller=new StatusPoller(status);CountDownLatch finished=new CountDownLatch(1);
            try{
                Request request=new Request.Builder().url(server.url("/status")).build();
                assertTrue(poller.start(request,discard(finished)));
                assertTrue(statusReceived.await(3,TimeUnit.SECONDS));
                for(int i=0;i<20;i++)assertFalse(poller.start(request,discard(new CountDownLatch(1))));
                CountDownLatch chatDone=new CountDownLatch(1);
                chat.newCall(new Request.Builder().url(server.url("/ask")).build()).enqueue(discard(chatDone));
                assertTrue("chat was starved by the stalled status request",chatDone.await(3,TimeUnit.SECONDS));
                assertEquals(1,polls.get());
                releaseStatus.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));
            }finally{
                releaseStatus.countDown();poller.cancel();
                status.dispatcher().executorService().shutdownNow();chat.dispatcher().executorService().shutdownNow();
                status.connectionPool().evictAll();chat.connectionPool().evictAll();
            }
        }
    }
    @Test public void mobileDnsPrefersIpv4AndRetainsIpv6Fallback() throws Exception {
        InetAddress v6=InetAddress.getByName("2001:db8::1"),a=InetAddress.getByName("192.0.2.1"),b=InetAddress.getByName("192.0.2.2");
        List<InetAddress> original=Arrays.asList(v6,a,b);
        assertEquals(Arrays.asList(a,b,v6),NetworkClients.ipv4First(host->original).lookup("example.test"));
        assertEquals(Arrays.asList(v6),NetworkClients.ipv4First(host->Arrays.asList(v6)).lookup("v6.example.test"));
        assertEquals(Arrays.asList(v6,a,b),original);
    }
    @Test public void cancelledStatusReleasesSlotWithoutWaitingForOldResponse() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setBody("ok"));server.start();
            OkHttpClient status=NetworkClients.status();StatusPoller poller=new StatusPoller(status);
            AtomicInteger obsolete=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);
            try{
                Request request=new Request.Builder().url(server.url("/status")).build();
                poller.start(request,new Callback(){
                    public void onFailure(Call c,IOException e){obsolete.incrementAndGet();}
                    public void onResponse(Call c,Response r){r.close();obsolete.incrementAndGet();}
                });
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));
                poller.cancel();assertTrue(poller.start(request,discard(done)));
                assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(0,obsolete.get());
            }finally{poller.cancel();status.dispatcher().executorService().shutdownNow();status.connectionPool().evictAll();}
        }
    }
}
