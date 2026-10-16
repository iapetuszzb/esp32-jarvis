package com.iapetus.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.HttpUrl;
import okhttp3.Request;

final class Config {
    final String server, token, session;
    final boolean proactive;
    Config(String server, String token, String session, boolean proactive) {
        this.server = server.trim().replaceAll("/+$", "");
        this.token = token.trim(); this.session = session.trim(); this.proactive = proactive;
    }
    static Config load(Context c) {
        SharedPreferences p=c.getSharedPreferences("jarvis",Context.MODE_PRIVATE);
        return new Config(p.getString("server",BuildConfig.DEFAULT_SERVER),p.getString("token",""),
                p.getString("session","jarvis"),p.getBoolean("proactive",false));
    }
    void save(Context c) {
        c.getSharedPreferences("jarvis",Context.MODE_PRIVATE).edit().putString("server",server)
            .putString("token",token).putString("session",session).putBoolean("proactive",proactive).apply();
    }
    static boolean privateHost(String host) {
        if (host.equals("localhost") || host.equals("127.0.0.1")) return true;
        String[] a=host.split("\\.");
        if(a.length!=4)return false;
        try {
            int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(a[i]);if(n[i]<0||n[i]>255)return false;}
            return n[0]==10 || (n[0]==192&&n[1]==168) || (n[0]==172&&n[1]>=16&&n[1]<=31);
        } catch(NumberFormatException e){return false;}
    }
    void validate() {
        HttpUrl u=HttpUrl.parse(server);
        if(u==null || !u.username().isEmpty() || !u.password().isEmpty() || u.query()!=null || u.fragment()!=null)
            throw new IllegalArgumentException("请填写有效的服务器 HTTPS 地址");
        if(!u.isHttps() && !privateHost(u.host()))throw new IllegalArgumentException("公网服务器必须使用 HTTPS");
        if(token.length()<24 || token.length()>256)throw new IllegalArgumentException("访问密钥应为 24–256 个字符");
        if(!session.matches("[A-Za-z0-9_-]{1,48}"))throw new IllegalArgumentException("会话 ID 只支持字母、数字、短横线和下划线");
    }
    Request.Builder request(String path) {
        return new Request.Builder().url(server+path).header("Authorization","Bearer "+token);
    }
}
