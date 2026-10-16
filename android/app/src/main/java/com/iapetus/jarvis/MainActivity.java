package com.iapetus.jarvis;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private EditText server,token,session,question,hotspotName,hotspotPassword,pairCode;
    private Switch proactive;
    private TextView status,camera,microphone,history;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final OkHttpClient http=NetworkClients.requests();
    private final StatusPoller cameraPoll=new StatusPoller(NetworkClients.status());
    private final StatusPoller healthPoll=new StatusPoller(NetworkClients.status());
    private String pendingAction=JarvisService.START;
    private boolean visible=false;
    private MediaPlayer reply;
    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            status.setText(i.getStringExtra("status"));microphone.setText(i.getStringExtra("mic"));
            String words=i.getStringExtra("text");if(words!=null&&!words.isEmpty())append("Jarvis",words);
        }
    };
    private final Runnable poll=new Runnable(){@Override public void run(){if(visible){checkCamera();ui.postDelayed(this,5000);}}};

    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout card(LinearLayout parent,String title){
        LinearLayout box=vertical();box.setPadding(dp(16),dp(12),dp(16),dp(16));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(14));box.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(12),0,0);parent.addView(box,lp);
        TextView h=text(title,19,Color.rgb(27,50,78));h.setTypeface(null,Typeface.BOLD);box.addView(h);return box;
    }
    private EditText field(LinearLayout parent,String label,String value,boolean secret){
        parent.addView(text(label,13,Color.rgb(79,97,119)));
        EditText e=new EditText(this);e.setText(value);e.setTextSize(15);e.setSingleLine(true);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;
    }
    private Button button(LinearLayout p,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());p.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(27,50,78));getWindow().setNavigationBarColor(Color.rgb(237,242,247));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=vertical();root.setPadding(dp(18),dp(18),dp(18),dp(28));root.setBackgroundColor(Color.rgb(237,242,247));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        TextView title=text("JARVIS",32,Color.rgb(27,50,78));title.setTypeface(null,Typeface.BOLD);root.addView(title);
        root.addView(text("v"+BuildConfig.VERSION_NAME,12,Color.rgb(79,97,119)));
        root.addView(text("ESP32 的眼睛 · 耳机里的助手",15,Color.rgb(79,97,119)));
        LinearLayout state=card(root,"设备状态");status=text("准备就绪后，开启离线唤醒或立即开始对话",16,Color.rgb(34,79,140));state.addView(status);
        camera=text("摄像头状态待检查",14,Color.DKGRAY);state.addView(camera);microphone=text("蓝牙耳机将在开启语音时选择",14,Color.DKGRAY);state.addView(microphone);
        button(state,"开启 Hey Jarvis",()->startVoice(JarvisService.START));
        button(state,"立即开始对话",()->startVoice(JarvisService.MANUAL));
        button(state,"结束本轮，继续等待唤醒",()->startService(new Intent(this,JarvisService.class).setAction(JarvisService.SLEEP)));
        button(state,"停止全部语音监听",()->stopService(new Intent(this,JarvisService.class)));

        Config c=Config.load(this);LinearLayout connection=card(root,"服务器连接");
        server=field(connection,"服务器 HTTPS 地址",c.server,false);server.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        token=field(connection,"访问密钥（仅保存在本机）",c.token,true);session=field(connection,"设备会话 ID，需与摄像头相同",c.session,false);
        pairCode=field(connection,"一次性配对码（首次使用，可替代手填访问密钥）","",true);
        button(connection,"使用配对码连接",this::pairServer);
        proactive=new Switch(this);proactive.setText("持续观察：无需每轮唤醒，允许主动提醒");proactive.setChecked(c.proactive);proactive.setPadding(0,dp(8),0,dp(8));connection.addView(proactive);
        button(connection,"保存并检查连接",()->{if(saveConfig()){stopService(new Intent(this,JarvisService.class));cameraPoll.cancel();checkCamera();}});
        button(connection,"检查服务器网络",this::checkHealth);
        button(connection,"用浏览器检查服务器",()->{
            HttpUrl u=HttpUrl.parse(server.getText().toString().trim());
            if(u==null||(!u.isHttps()&&!Config.privateHost(u.host()))||!u.username().isEmpty()||!u.password().isEmpty()||u.query()!=null||u.fragment()!=null){toast("请填写有效的服务器 HTTPS 地址");return;}
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u.toString().replaceAll("/+$","")+"/health")));
        });
        button(connection,"导入连接配置 JSON",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,20);});
        connection.addView(text("持续观察开启后，点击“开启”或“立即开始”进入会话。首次使用需要麦克风和附近设备权限。",13,Color.DKGRAY));

        LinearLayout chat=card(root,"文字看图提问");question=field(chat,"语音不可用时，也可以直接问", "眼前画面里有什么？",false);
        button(chat,"发送文字问题并播放回答",this::ask);
        history=text("",15,Color.rgb(27,50,78));history.setTextIsSelectable(true);chat.addView(history);

        LinearLayout setup=card(root,"摄像头首次配网");
        setup.addView(text("① 先关闭手机热点，连接 Jarvis-Cam-xxxxxx Wi-Fi，密码 jarvis-setup。\n② 填入你之后准备开启的手机热点名称和密码，写入摄像头。\n③ 离开摄像头 Wi-Fi，开启 2.4 GHz 手机热点。摄像头直接向服务器上传；本 App 不转发视频。",14,Color.DKGRAY));
        hotspotName=field(setup,"手机热点名称", "",false);hotspotPassword=field(setup,"手机热点密码", "",true);
        button(setup,"打开手机 Wi-Fi 设置",()->startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(setup,"把连接配置写入摄像头",this::provisionCamera);
        button(setup,"浏览器打开摄像头配网页面",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("http://192.168.4.1"))));
        LinearLayout help=card(root,"使用提示");help.addView(text("先在系统蓝牙设置中连接 Enco R4，并启用通话音频。App 会优先选择耳机通话麦克风，不可用时明确显示手机麦克风。\n\n锁屏使用时，请在 ColorOS 中允许本 App 后台活动，并取消电池优化。首次开启后先在前台验证唤醒、收音和播放。\n\n声音由 MiniCPM 生成。画面和语音只发到你配置的服务器；唤醒监听在手机本地执行。",14,Color.DKGRAY));
    }

    private Config form(){return new Config(server.getText().toString(),token.getText().toString(),session.getText().toString(),proactive.isChecked());}
    private boolean saveConfig(){try{Config c=form();c.validate();c.save(this);return true;}catch(Exception e){toast(e.getMessage());return false;}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void pairServer(){
        String code=pairCode.getText().toString().trim();
        try{
            Config c=new Config(server.getText().toString(),"pairing-placeholder-token-123456","jarvis",false);c.validate();
            JSONObject b=new JSONObject().put("code",code);
            http.newCall(new Request.Builder().url(c.server+"/v1/pair").post(RequestBody.create(b.toString(),MediaType.get("application/json"))).build()).enqueue(new Callback(){
                public void onFailure(Call call,IOException e){ui.post(()->toast("配对连接失败："+e.getMessage()));}
                public void onResponse(Call call,Response response)throws IOException{try(Response r=response){
                    if(!r.isSuccessful()){ui.post(()->toast(r.code()==429?"尝试过多，请一分钟后再试":"配对码错误、已使用或过期；也可导入连接配置 JSON"));return;}
                    try{JSONObject j=new JSONObject(r.body().string());ui.post(()->{stopService(new Intent(MainActivity.this,JarvisService.class));token.setText(j.optString("token"));session.setText(j.optString("session","jarvis"));pairCode.setText("");saveConfig();checkCamera();toast("服务器配对成功");});}
                    catch(Exception e){ui.post(()->toast("配对返回格式无效"));}
                }}
            });
        }catch(Exception e){toast("请先填写有效服务器地址和配对码");}
    }
    private void append(String who,String message){String s=history.getText()+"\n"+who+"："+message+"\n";if(s.length()>6000)s=s.substring(s.length()-6000);history.setText(s);}

    private void startVoice(String action){
        if(!saveConfig())return;pendingAction=action;
        ArrayList<String> perms=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)perms.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!perms.isEmpty())requestPermissions(perms.toArray(new String[0]),10);else launchVoice();
    }
    private void launchVoice(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){toast("需要麦克风权限才能唤醒和对话");return;}
        try{startForegroundService(new Intent(this,JarvisService.class).setAction(pendingAction));status.setText("正在准备语音服务…");}
        catch(Exception e){toast("无法开启语音："+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==10)launchVoice();}

    private String networkFailure(IOException e){
        if(e instanceof java.net.UnknownHostException)return "域名解析失败，请检查手机网络或私人 DNS 设置";
        if(e instanceof javax.net.ssl.SSLException)return "HTTPS 连接失败，请检查手机时间及网络连接";
        if(e instanceof java.net.SocketTimeoutException||e instanceof java.io.InterruptedIOException)
            return "连接服务器超时；请点“检查服务器网络”，并检查本应用的移动数据权限";
        return "连接中断（"+e.getClass().getSimpleName()+"），请点“检查服务器网络”";
    }
    private boolean sameConnection(Config c){Config now=form();return c.server.equals(now.server)&&c.token.equals(now.token)&&c.session.equals(now.session);}
    private void cameraMessage(Config c,String message){ui.post(()->{if(visible&&sameConnection(c))camera.setText(message);});}
    private void checkHealth(){
        Config c=form();try{c.validateServer();}catch(Exception e){toast(e.getMessage());return;}
        status.setText("正在检查服务器网络，最多等待约 12 秒…");
        healthPoll.cancel();healthPoll.start(new Request.Builder().url(c.server+"/health").build(),new Callback(){
            public void onFailure(Call call,IOException e){ui.post(()->{if(visible&&sameConnection(c))status.setText(networkFailure(e));});}
            public void onResponse(Call call,Response response)throws IOException{try(Response r=response){
                String result;
                try{JSONObject j=new JSONObject(r.body().string());result=r.isSuccessful()&&j.optBoolean("ready")?"网络连接正常 · 模型已就绪。若仍无法对话，请检查访问密钥或结束其他会话。":"服务器返回 HTTP "+r.code()+"，请确认地址";}
                catch(IOException e){result=networkFailure(e);}
                catch(Exception e){result="地址可以访问，但返回内容不是 Jarvis 服务，请确认完整地址";}
                final String message=result;ui.post(()->{if(visible&&sameConnection(c))status.setText(message);});
            }}
        });
    }
    private void checkCamera(){
        Config c=form();try{c.validate();}catch(Exception ignored){return;}
        cameraPoll.start(c.request("/v1/status/"+c.session).build(),new Callback(){
            public void onFailure(Call call,IOException e){cameraMessage(c,networkFailure(e));}
            public void onResponse(Call call,Response response)throws IOException{try(Response r=response){
                if(!r.isSuccessful()){cameraMessage(c,r.code()==401?"已连到服务器 · 访问密钥无效，请重新导入连接 JSON":"服务器 HTTP "+r.code()+"，请检查地址");return;}
                try{JSONObject s=new JSONObject(r.body().string());String message;
                    if(s.optBoolean("camera_online"))message="摄像头在线 · 已收到 "+s.optInt("sequence")+" 帧";
                    else if(s.optInt("sequence")>0)message="服务器在线 · 摄像头已断流，上次画面约 "+Math.round(s.optDouble("frame_age_seconds"))+" 秒前";
                    else message="服务器在线 · 等待同一会话的摄像头画面";
                    cameraMessage(c,message);}
                catch(IOException e){cameraMessage(c,networkFailure(e));}
                catch(Exception e){cameraMessage(c,"服务器返回格式不正确");}
            }}
        });
    }

    private void ask(){
        if(!saveConfig())return;String q=question.getText().toString().trim();if(q.isEmpty())return;
        Config c=form();append("我",q);status.setText("正在结合最新画面回答…");
        try{
            JSONObject body=new JSONObject().put("session",c.session).put("question",q);
            http.newCall(c.request("/v1/ask").post(RequestBody.create(body.toString(),MediaType.get("application/json"))).build()).enqueue(new Callback(){
                public void onFailure(Call call,IOException e){ui.post(()->status.setText("提问失败："+networkFailure(e)));}
                public void onResponse(Call call,Response response)throws IOException{try(Response r=response){
                    if(!r.isSuccessful()){ui.post(()->status.setText(r.code()==409?"请先结束正在运行的语音会话，再发送文字问题":"模型请求失败 HTTP "+r.code()));return;}
                    try{JSONObject o=new JSONObject(r.body().string());byte[] wav=android.util.Base64.decode(o.getString("wav"),android.util.Base64.DEFAULT);
                        File f=File.createTempFile("jarvis-reply-",".wav",getCacheDir());try(FileOutputStream out=new FileOutputStream(f)){out.write(wav);}
                        ui.post(()->{append("Jarvis",o.optString("text"));status.setText(o.optBoolean("camera_online")?"已结合最新画面回答":"当前没有新画面，仅回答文字问题");playFile(f);});
                    }catch(Exception e){ui.post(()->status.setText("无法解析模型回答"));}
                }}
            });
        }catch(Exception e){toast(e.getMessage());}
    }
    private void playFile(File f){
        try{if(reply!=null)reply.release();reply=new MediaPlayer();reply.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            reply.setDataSource(f.getAbsolutePath());reply.setOnCompletionListener(p->{p.release();if(reply==p)reply=null;f.delete();});reply.prepare();reply.start();
        }catch(Exception e){toast("播放失败："+e.getMessage());f.delete();}
    }

    private void provisionCamera(){
        if(!saveConfig())return;Config c=form();String ssid=hotspotName.getText().toString(),pass=hotspotPassword.getText().toString();
        if(ssid.isEmpty()||pass.length()<8){toast("请填写之后准备开启的手机热点名称和至少 8 位密码");return;}
        status.setText("正在通过摄像头 Wi-Fi 写入配置…");
        OkHttpClient.Builder builder=http.newBuilder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS);
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        for(Network net:cm.getAllNetworks()){
            NetworkCapabilities cap=cm.getNetworkCapabilities(net);
            if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){
                builder.socketFactory(net.getSocketFactory()).dns(host->Arrays.asList(net.getAllByName(host)));break;
            }
        }
        OkHttpClient local=builder.build();
        new Thread(()->{
            try(Response info=local.newCall(new Request.Builder().url("http://192.168.4.1/setup-info").build()).execute()){
                if(!info.isSuccessful())throw new IOException("摄像头配网窗口未开启，请按住 BOOT 3 秒");
                JSONObject json=new JSONObject(info.body().string());
                FormBody b=new FormBody.Builder().add("setup_code",json.getString("setup_code")).add("ssid",ssid).add("wifi_password",pass)
                    .add("server",c.server).add("token",c.token).add("session",c.session).build();
                try(Response r=local.newCall(new Request.Builder().url("http://192.168.4.1/configure").post(b).build()).execute()){
                    if(!r.isSuccessful())throw new IOException("配置保存失败 HTTP "+r.code());
                    ui.post(()->{status.setText("摄像头配置已保存。现在离开摄像头 Wi-Fi，开启 2.4 GHz 手机热点。");hotspotPassword.setText("");});
                }
            }catch(Exception e){ui.post(()->status.setText("摄像头配网失败："+e.getMessage()+"。请确认已连接 Jarvis-Cam Wi-Fi。"));}
        },"jarvis-provision").start();
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==20&&result==RESULT_OK&&data!=null){
            try(InputStream in=getContentResolver().openInputStream(data.getData())){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>16384)throw new IOException("配置文件过大");}
                JSONObject j=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                Config c=new Config(j.getString("server"),j.getString("token"),j.optString("session","jarvis"),false);c.validate();
                stopService(new Intent(this,JarvisService.class));c.save(this);server.setText(c.server);token.setText(c.token);session.setText(c.session);proactive.setChecked(false);checkCamera();toast("连接配置已导入");
            }catch(Exception e){toast("无法导入配置："+e.getMessage());}
        }
    }
    @Override protected void onStart(){super.onStart();visible=true;IntentFilter f=new IntentFilter(JarvisService.EVENT);androidx.core.content.ContextCompat.registerReceiver(this,events,f,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);ui.post(poll);}
    @Override protected void onStop(){visible=false;ui.removeCallbacks(poll);cameraPoll.cancel();healthPoll.cancel();unregisterReceiver(events);super.onStop();}
    @Override protected void onDestroy(){if(reply!=null){reply.release();reply=null;}super.onDestroy();}
}
