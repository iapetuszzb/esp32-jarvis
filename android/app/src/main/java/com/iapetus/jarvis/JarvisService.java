package com.iapetus.jarvis;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.*;
import android.util.Base64;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import okhttp3.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class JarvisService extends Service {
    public static final String EVENT="com.iapetus.jarvis.EVENT";
    public static final String START="start", MANUAL="manual", STOP="stop", SLEEP="sleep";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final OkHttpClient http=NetworkClients.live();
    private Config config;
    private volatile boolean running, ready, awake, socketReady, pendingManual;
    private volatile long lastVoiceAt, lastAck=-1;
    private long sequence=0, generation=0;
    private WebSocket socket;
    private AudioRecord recorder;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private int oldMode;
    private PcmPlayer player;
    private Thread microphoneThread;
    private PowerManager.WakeLock wakeLock;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private String micRoute="正在选择麦克风";

    private void event(String status,String text) {
        Intent i=new Intent(EVENT).setPackage(getPackageName()).putExtra("status",status)
            .putExtra("text",text).putExtra("awake",awake).putExtra("mic",micRoute);
        sendBroadcast(i);
    }

    private Notification notification(String text) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,JarvisService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"jarvis-mic").setContentTitle("Jarvis 视觉助手")
            .setContentText(text).setSmallIcon(com.iapetus.jarvis.R.drawable.ic_jarvis).setContentIntent(pi)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("jarvis-mic","语音助手与唤醒监听",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?STOP:intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(SLEEP.equals(action)){if(running)endSession("已结束对话，等待 Hey Jarvis");else stopSelf();return START_NOT_STICKY;}
        if(running){
            Config desired=Config.load(this);
            if(!desired.server.equals(config.server)||!desired.token.equals(config.token)||!desired.session.equals(config.session)||desired.proactive!=config.proactive){
                endSession("连接配置已更新");config=desired;
            }
        }
        pendingManual|=MANUAL.equals(action);
        if(!running){
            config=Config.load(this);
            try{config.validate();startForeground(1,notification("正在准备麦克风与离线唤醒"));}
            catch(Exception e){event("无法启动："+e.getMessage(),"");stopSelf();return START_NOT_STICKY;}
            running=true;
            PowerManager pm=getSystemService(PowerManager.class);
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Jarvis:Microphone");wakeLock.acquire(6*60*60*1000L);
            microphoneThread=new Thread(this::microphoneLoop,"jarvis-microphone");microphoneThread.start();
        }else if(ready && (pendingManual || config.proactive)){pendingManual=false;beginSession();}
        return START_NOT_STICKY;
    }

    private File unpackModel() throws IOException {
        File dest=new File(getFilesDir(),"wake-model-en-us-0.15");
        if(!new File(dest,"am/final.mdl").isFile() || !new File(dest,".ready").isFile()){
            event("首次启动：正在准备离线 Hey Jarvis 模型…","");
            copyAsset("vosk-model-small-en-us-0.15",dest);
            try(FileOutputStream out=new FileOutputStream(new File(dest,".ready"))){out.write(1);}
        }
        return dest;
    }

    private void copyAsset(String name,File dest) throws IOException {
        String[] children=getAssets().list(name);
        if(children!=null&&children.length>0){
            if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("无法创建唤醒模型目录");
            for(String child:children)copyAsset(name+"/"+child,new File(dest,child));
        }else try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(dest)){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }

    private void routeAudio() throws InterruptedException {
        audioManager=getSystemService(AudioManager.class);oldMode=audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS)main.post(()->{event("音频被其他通话占用，已停止监听","");stopSelf();});}).build();
        audioManager.requestAudioFocus(focus);
        micRoute="手机麦克风（未检测到可用的耳机通话通道）";
        try {
            if(Build.VERSION.SDK_INT>=31){
                AudioDeviceInfo selected=null;
                for(AudioDeviceInfo d:audioManager.getAvailableCommunicationDevices())
                    if(d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET){selected=d;break;}
                if(selected!=null && audioManager.setCommunicationDevice(selected)){
                    for(int i=0;i<24&&running;i++){
                        AudioDeviceInfo current=audioManager.getCommunicationDevice();
                        if(current!=null&&current.getId()==selected.getId()){micRoute="蓝牙耳机："+current.getProductName();break;}
                        Thread.sleep(200);
                    }
                }
            }else if(audioManager.isBluetoothScoAvailableOffCall()){
                audioManager.startBluetoothSco();
                for(int i=0;i<24&&running;i++){if(audioManager.isBluetoothScoOn()){micRoute="蓝牙耳机通话麦克风";break;}Thread.sleep(200);}
            }
        }catch(SecurityException e){micRoute="手机麦克风（请授予附近设备权限后重新开启）";}
    }

    private void microphoneLoop() {
        Model model=null;Recognizer recognizer=null;
        try{
            File modelDir=unpackModel();if(!running)return;
            model=new Model(modelDir.getAbsolutePath());
            if(!running)return;
            recognizer=new Recognizer(model,16000,"[\"hey jarvis\",\"[unk]\"]");
            routeAudio();if(!running)return;
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("请授予麦克风权限后重新启动");
            int bufferSize=Math.max(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize*2);
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("麦克风初始化失败");
            if(AcousticEchoCanceler.isAvailable()){aec=AcousticEchoCanceler.create(recorder.getAudioSessionId());if(aec!=null)aec.setEnabled(true);}
            if(NoiseSuppressor.isAvailable()){ns=NoiseSuppressor.create(recorder.getAudioSessionId());if(ns!=null)ns.setEnabled(true);}
            player=new PcmPlayer();recorder.startRecording();ready=true;
            main.post(()->{event("离线唤醒已开启，请说 Hey Jarvis","");if(pendingManual||config.proactive){pendingManual=false;beginSession();}});
            byte[] buffer=new byte[3200], chunk=new byte[32000];int filled=0;
            boolean wasAwake=false;
            while(running){
                int n=recorder.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if(n<=0){if(running)throw new IOException("麦克风读取失败："+n);break;}
                if(!awake){
                    if(wasAwake){recognizer.reset();wasAwake=false;}
                    filled=0;
                    boolean complete=recognizer.acceptWaveForm(buffer,n);
                    JSONObject result=new JSONObject(complete?recognizer.getResult():recognizer.getPartialResult());
                    if(WakeWords.detected(result.optString(complete?"text":"partial")))main.post(this::beginSession);
                }else{
                    wasAwake=true;
                    double energy=0;for(int i=0;i+1<n;i+=2){int s=(short)((buffer[i]&255)|(buffer[i+1]<<8));energy+=(double)s*s;}
                    if(Math.sqrt(energy/Math.max(1,n/2))>650)lastVoiceAt=SystemClock.elapsedRealtime();
                    if(!config.proactive && SystemClock.elapsedRealtime()-lastVoiceAt>60000){main.post(()->endSession("60 秒没有检测到说话，已回到唤醒监听"));}
                    if(!socketReady){filled=0;continue;}
                    int at=0;
                    while(at<n){int take=Math.min(n-at,32000-filled);System.arraycopy(buffer,at,chunk,filled,take);at+=take;filled+=take;
                        if(filled==32000){sendAudio(Arrays.copyOf(chunk,chunk.length));filled=0;}}
                }
            }
        }catch(Exception e){if(running)main.post(()->{event("语音启动失败："+e.getMessage(),"");stopSelf();});}
        finally{ready=false;if(recognizer!=null)recognizer.close();if(model!=null)model.close();}
    }

    private void beginSession() {
        if(!running||!ready||awake)return;
        awake=true;socketReady=false;lastVoiceAt=SystemClock.elapsedRealtime();sequence=0;lastAck=-1;
        final long epoch=++generation;
        if(player!=null)player.flush();
        event("已唤醒，正在连接视觉语音模型…","");
        Request request=config.request("/v1/live?session="+config.session).build();
        socket=http.newWebSocket(request,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket,Response response){
                if(epoch!=generation){webSocket.close(1000,"superseded");return;}
                try{JSONObject start=new JSONObject().put("type","start").put("proactive",config.proactive);webSocket.send(start.toString());}
                catch(Exception e){main.post(()->endSession("会话初始化失败"));}
            }
            @Override public void onMessage(WebSocket webSocket,String text){
                if(epoch!=generation)return;
                try{
                    JSONObject m=new JSONObject(text);String type=m.optString("type");
                    if(type.equals("ready")){socketReady=true;event("正在看、听，请直接说话","");}
                    else if(type.equals("output")){
                        lastAck=m.optLong("seq",lastAck);
                        String words=m.optString("text");String pcm=m.optString("pcm");
                        if(!pcm.isEmpty()&&player!=null&&!player.offer(Base64.decode(pcm,Base64.DEFAULT)))
                            main.post(()->endSession("语音播放积压，请重新开始"));
                        event((m.optBoolean("camera_online")?"画面在线":"暂无新画面，当前仅语音")+" · "+m.optInt("processing_ms")+" ms",words);
                    }else if(type.equals("error")){main.post(()->endSession(m.optString("message","模型会话失败")));}
                }catch(Exception e){main.post(()->endSession("服务器返回的数据格式不正确"));}
            }
            @Override public void onClosed(WebSocket webSocket,int code,String reason){
                if(epoch==generation)main.post(()->endSession("会话已结束，等待 Hey Jarvis"));
            }
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){
                if(epoch==generation)main.post(()->endSession(response!=null?"连接失败 HTTP "+response.code():"连接中断："+t.getMessage()));
            }
        });
    }

    private void sendAudio(byte[] pcm) {
        WebSocket ws=socket;if(ws==null||!socketReady)return;
        try{
            long seq=sequence++;
            if(ws.queueSize()>200000||seq-lastAck>5){main.post(()->endSession("音频上传积压，请检查网络后重新唤醒"));return;}
            JSONObject m=new JSONObject().put("type","audio").put("seq",seq).put("pcm",Base64.encodeToString(pcm,Base64.NO_WRAP));
            if(!ws.send(m.toString()))main.post(()->endSession("语音连接已关闭"));
        }catch(Exception e){main.post(()->endSession("音频发送失败"));}
    }

    private void endSession(String reason) {
        generation++;socketReady=false;awake=false;
        if(socket!=null){socket.send("{\"type\":\"stop\"}");socket.close(1000,"user finished");socket=null;}
        if(player!=null)player.flush();event(reason,"");
        if(running&&config!=null&&config.proactive) {
            // No reconnect loop: the user can explicitly restart after inspecting the error.
            pendingManual=false;
        }
    }

    @Override public void onDestroy(){
        running=false;endSession("语音监听已停止");
        if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}}
        if(microphoneThread!=null){try{microphoneThread.join(2000);}catch(InterruptedException ignored){}}
        if(recorder!=null){recorder.release();recorder=null;}
        if(player!=null){player.close();player=null;}
        if(aec!=null)aec.release();if(ns!=null)ns.release();
        if(audioManager!=null){try{if(Build.VERSION.SDK_INT>=31)audioManager.clearCommunicationDevice();else audioManager.stopBluetoothSco();
            if(focus!=null)audioManager.abandonAudioFocusRequest(focus);audioManager.setMode(oldMode);}catch(Exception ignored){}}
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
