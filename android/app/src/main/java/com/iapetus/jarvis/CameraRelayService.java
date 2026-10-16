package com.iapetus.jarvis;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.Log;
import java.net.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Owns one partial UDP frame, one pending latest frame and at most one HTTP upload. */
public final class CameraRelayService extends Service {
    static final String EVENT="com.iapetus.jarvis.RELAY";
    private final Object lock=new Object();
    private volatile boolean running;
    private DatagramSocket socket;
    private ServerSocket tcpServer;
    private volatile Socket tcpClient;
    private UdpCameraAssembler assembler;
    private UdpCameraAssembler.Frame pending;
    private Config config;
    private OkHttpClient http;
    private Thread receiver,tcpReceiver,uploader;
    private PowerManager.WakeLock wake;
    private long lastNotice;
    private volatile int received,uploaded;
    private int packets,authErrors;
    private long lastCaptured;
    private volatile String transport="UDP";

    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("camera-relay","摄像头中转",NotificationManager.IMPORTANCE_LOW));
        Notification n=notification("等待 ESP32 画面 · 手机热点须开启");
        if(Build.VERSION.SDK_INT>=29)startForeground(2,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(2,n);
    }
    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"camera-relay").setSmallIcon(com.iapetus.jarvis.R.drawable.ic_jarvis)
            .setContentTitle("Jarvis 摄像头中转").setContentText(message).setContentIntent(open).setOngoing(true).build();
    }
    private void report(String message){
        if(!running)return;
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("message",message));
        long now=SystemClock.elapsedRealtime();
        if(now-lastNotice>3000){lastNotice=now;getSystemService(NotificationManager.class).notify(2,notification(message));}
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(running)return START_NOT_STICKY;
        try{
            config=Config.load(this);config.validate();
            assembler=new UdpCameraAssembler(config.token,config.session);
            http=NetworkClients.requests().newBuilder().connectTimeout(4,TimeUnit.SECONDS)
                .readTimeout(4,TimeUnit.SECONDS).writeTimeout(4,TimeUnit.SECONDS).callTimeout(6,TimeUnit.SECONDS).build();
            socket=new DatagramSocket(null);socket.setReuseAddress(true);
            socket.setReceiveBufferSize(256*1024);
            socket.bind(new InetSocketAddress(UdpCameraAssembler.PORT));socket.setSoTimeout(250);
            tcpServer=new ServerSocket();tcpServer.setReuseAddress(true);
            tcpServer.bind(new InetSocketAddress(19422),1);tcpServer.setSoTimeout(250);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"jarvis:camera-relay");
            wake.acquire(6*60*60*1000L);running=true;
            receiver=new Thread(this::receive,"jarvis-camera-udp");uploader=new Thread(this::upload,"jarvis-camera-upload");
            tcpReceiver=new Thread(this::receiveTcp,"jarvis-camera-tcp");
            receiver.start();tcpReceiver.start();uploader.start();report("中转已开启 · 等待摄像头 · 自动选择 TCP / UDP");
        }catch(Exception e){sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("message","中转启动失败："+e.getClass().getSimpleName()));stopSelf();}
        return START_NOT_STICKY;
    }
    private void receive(){
        byte[] bytes=new byte[1201];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
        long started=SystemClock.elapsedRealtime(),lastPacket=started;
        try{
            while(running){
                try{
                    packet.setLength(bytes.length);socket.receive(packet);
                    InetAddress peer=packet.getAddress();
                    if(!localPeer(peer))continue;
                    packets++;
                    UdpCameraAssembler.Frame frame=assembler.accept(bytes,packet.getLength(),System.currentTimeMillis(),SystemClock.elapsedRealtime());
                    if(frame!=null){offer(frame,"UDP");lastPacket=SystemClock.elapsedRealtime();}
                }catch(SocketTimeoutException ignored){assembler.expire(SystemClock.elapsedRealtime());}
                catch(java.security.GeneralSecurityException ignored){authErrors++;}
                synchronized(lock){if(pending!=null&&!pending.fresh(System.currentTimeMillis(),SystemClock.elapsedRealtime()))pending=null;}
                if(SystemClock.elapsedRealtime()-lastPacket>5000){
                    String message=packets==0?"尚未收到摄像头数据 · 检查热点与固件":
                        "未收到完整画面 · 数据包 "+packets+" · 验证失败 "+authErrors;
                    if(!"TCP".equals(transport))report(message);lastPacket=SystemClock.elapsedRealtime();
                    Log.i("JarvisCameraRelay","packets="+packets+" auth_errors="+authErrors+
                        " complete="+received+" uploaded="+uploaded+" partial_bytes="+assembler.retainedBytes());
                }
            }
        }catch(Exception e){if(running){report("UDP 接收中断："+e.getClass().getSimpleName());stopSelf();}}
        finally{assembler.clear();}
    }
    private static boolean localPeer(InetAddress peer){
        return peer instanceof Inet4Address&&(peer.isSiteLocalAddress()||peer.isLoopbackAddress());
    }
    private void offer(UdpCameraAssembler.Frame frame,String via){
        synchronized(lock){
            if(frame.capturedAt<lastCaptured)return;
            lastCaptured=frame.capturedAt;transport=via;received++;
            pending=frame;lock.notifyAll(); // replacing releases previous pending JPEG
        }
    }
    private void receiveTcp(){
        byte[] bytes=new byte[4096];
        while(running){
            try{
                try{tcpClient=tcpServer.accept();}catch(SocketTimeoutException ignored){continue;}
                Socket client=tcpClient;
                if(!localPeer(client.getInetAddress())){
                    client.close();continue;
                }
                client.setSoTimeout(250);client.setTcpNoDelay(true);
                InputStream input=client.getInputStream();OutputStream output=client.getOutputStream();
                CameraTcpFramer framer=new CameraTcpFramer();long lastData=SystemClock.elapsedRealtime();
                Log.i("JarvisCameraRelay","TCP camera connected");
                while(running&&!client.isClosed()){
                    long now=SystemClock.elapsedRealtime();
                    try{
                        int count=input.read(bytes);if(count<0)break;
                        lastData=SystemClock.elapsedRealtime();
                        framer.accept(bytes,count,lastData,(packet,length)->{
                            UdpCameraAssembler.Frame frame=assembler.accept(packet,length,System.currentTimeMillis(),SystemClock.elapsedRealtime());
                            if(frame!=null){
                                offer(frame,"TCP");
                                output.write(ByteBuffer.allocate(8).putInt(0x4a434131).putInt(ByteBuffer.wrap(packet).getInt(16)).array());
                                output.flush();
                            }
                        });
                    }catch(SocketTimeoutException ignored){
                        framer.expire(now);assembler.expire(now);
                        synchronized(lock){if(pending!=null&&!pending.fresh(System.currentTimeMillis(),now))pending=null;}
                        if(now-lastData>2500)break;
                    }
                }
            }catch(Exception e){if(running)Log.i("JarvisCameraRelay","TCP reconnect: "+e.getClass().getSimpleName());}
            finally{Socket client=tcpClient;tcpClient=null;if(client!=null)try{client.close();}catch(IOException ignored){}}
        }
    }
    private void upload(){
        while(running){
            UdpCameraAssembler.Frame frame;
            synchronized(lock){
                if(pending==null){try{lock.wait(250);}catch(InterruptedException e){return;}}
                frame=pending;pending=null;
            }
            if(frame==null||!frame.fresh(System.currentTimeMillis(),SystemClock.elapsedRealtime()))continue;
            try(Response response=http.newCall(config.request("/v1/frame/"+config.session)
                .header("X-Frame-Captured-At-Ms",Long.toString(frame.capturedAt))
                .post(RequestBody.create(frame.jpeg,MediaType.get("image/jpeg"))).build()).execute()){
                if(response.body()!=null)response.body().string();
                if(response.isSuccessful()){uploaded++;report(transport+" 完整帧 "+received+" · 已上传 "+uploaded+" 帧");}
                else report("中转上传 HTTP "+response.code()+(response.code()==408?" · 旧画面已丢弃":""));
            }catch(IOException e){report("已收到 "+transport+" 画面 · 上传失败："+e.getClass().getSimpleName());}
            // No retry of this JPEG. Next iteration takes the newest unexpired frame.
        }
    }
    @Override public void onDestroy(){
        running=false;if(socket!=null)socket.close();
        if(tcpServer!=null)try{tcpServer.close();}catch(IOException ignored){}
        Socket client=tcpClient;if(client!=null)try{client.close();}catch(IOException ignored){}
        synchronized(lock){pending=null;lock.notifyAll();}
        if(http!=null){http.dispatcher().cancelAll();http.connectionPool().evictAll();}
        if(uploader!=null)uploader.interrupt();
        if(wake!=null&&wake.isHeld())wake.release();
        stopForeground(true);super.onDestroy();
    }
}
