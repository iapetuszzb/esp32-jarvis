#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <esp_camera.h>
#include <time.h>
#include "camera_pins.h"
#include "root_ca.h"

Preferences prefs;
WebServer portal(80);
String wifiSsid, wifiPassword, serverUrl, accessToken, sessionId, setupCode;
bool cameraReady = false, portalOpen = false, connectedOnce = false;
bool automaticPortalOpened = false;
WiFiClient plainUplink;
WiFiClientSecure tlsUplink;
HTTPClient uplink;
String uplinkUrl;
uint32_t retryInterval = 1000;
uint32_t portalStarted = 0, lastUpload = 0, uploadSequence = 0, lastConnectAttempt = 0;
int lastHttpStatus = 0;
constexpr uint32_t FRAME_INTERVAL_MS = 1000;
constexpr uint32_t SETUP_WINDOW_MS = 10 * 60 * 1000;

const char SETUP_HTML[] PROGMEM = R"HTML(<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Jarvis 摄像头配网</title>
<style>body{font:16px/1.7 sans-serif;max-width:600px;margin:24px auto;padding:16px;color:#172d42}input,button{box-sizing:border-box;width:100%;padding:12px;margin:6px 0 14px;font-size:16px}button{background:#224f8c;color:white;border:0;border-radius:8px}small{color:#526579}</style>
<h1>Jarvis 摄像头</h1><p>保存手机热点和服务器配置。随后打开手机的 2.4 GHz 热点，摄像头会直接上传画面。</p>
<form method="post" action="/configure"><input type="hidden" name="setup_code" id="code">
<label>手机热点名称<input name="ssid" id="ssid" maxlength="32" required></label>
<label>热点密码<input name="wifi_password" type="password" minlength="8" maxlength="63" required></label>
<label>服务器地址<input name="server" id="server" placeholder="https://your-server.example" required></label>
<label>访问密钥<input name="token" type="password" minlength="24" maxlength="256" required></label>
<label>设备会话 ID<input name="session" id="session" value="jarvis" pattern="[A-Za-z0-9_-]{1,48}" required></label>
<button>保存并连接</button></form><small>会话 ID 必须与安卓 App 相同。按住板上 BOOT 约 3 秒可重新打开配网页面，不会清空已有配置。</small>
<script>fetch('/setup-info').then(r=>r.json()).then(c=>{document.getElementById('code').value=c.setup_code;for(const k of ['ssid','server','session'])if(c[k])document.getElementById(k).value=c[k]});</script></html>)HTML";

bool validSession(const String &s) {
  if (s.isEmpty() || s.length() > 48) return false;
  for (size_t i=0; i<s.length(); i++) if (!isalnum(s[i]) && s[i]!='_' && s[i]!='-') return false;
  return true;
}

bool validServer(const String &s) {
  if (s.length() > 240 || s.indexOf('?')>=0 || s.indexOf('#')>=0 || s.indexOf('@')>=0 || s.indexOf(' ')>=0) return false;
  if (s.startsWith("https://") && s.length()>10) return true;
  // HTTP is supported only on a private LAN, never as a public cloud fallback.
  if (s.startsWith("http://")) {
    String host=s.substring(7); int slash=host.indexOf('/'); if(slash>=0)host=host.substring(0,slash);
    int colon=host.indexOf(':'); if(colon>=0)host=host.substring(0,colon);
    IPAddress ip;
    return ip.fromString(host) && (ip[0]==10 || (ip[0]==192 && ip[1]==168) ||
      (ip[0]==172 && ip[1]>=16 && ip[1]<=31));
  }
  return false;
}

void connectWifi() {
  if (wifiSsid.isEmpty()) return;
  WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
  lastConnectAttempt=millis();
}

void startPortal() {
  WiFi.mode(WIFI_AP_STA);
  String ap="Jarvis-Cam-"+String((uint32_t)(ESP.getEfuseMac() & 0xFFFFFF),HEX);
  WiFi.softAP(ap.c_str(), "jarvis-setup");
  setupCode=String(esp_random(),HEX)+String(esp_random(),HEX);
  portalOpen=true; portalStarted=millis();
  automaticPortalOpened=true;
  Serial.printf("SETUP AP: %s | password: jarvis-setup | http://192.168.4.1\n",ap.c_str());
}

void initPortal() {
  portal.on("/",HTTP_GET,[]{
    if (!portalOpen) { portal.send(403,"text/plain","Hold BOOT for 3 seconds to open setup"); return; }
    portal.sendHeader("Cache-Control","no-store");
    portal.send_P(200,"text/html; charset=utf-8",SETUP_HTML);
  });
  portal.on("/setup-info",HTTP_GET,[]{
    if(!portalOpen){portal.send(403);return;}
    StaticJsonDocument<768> d;
    d["setup_code"]=setupCode;d["ssid"]=wifiSsid;d["server"]=serverUrl;d["session"]=sessionId;
    d["camera_ready"]=cameraReady;d["wifi_connected"]=(WiFi.status()==WL_CONNECTED);
    d["last_http_status"]=lastHttpStatus;
    String s;serializeJson(d,s);portal.sendHeader("Cache-Control","no-store");portal.send(200,"application/json",s);
  });
  portal.on("/configure",HTTP_POST,[]{
    if(!portalOpen || portal.arg("setup_code")!=setupCode){portal.send(403,"text/plain","Reopen setup page");return;}
    String ssid=portal.arg("ssid"), pass=portal.arg("wifi_password"), url=portal.arg("server"),
      token=portal.arg("token"), session=portal.arg("session");
    url.trim();while(url.endsWith("/"))url.remove(url.length()-1);
    if(ssid.isEmpty()||ssid.length()>32||pass.length()<8||pass.length()>63||!validServer(url)||
        token.length()<24||token.length()>256||!validSession(session)){
      portal.send(400,"text/plain; charset=utf-8","配置无效：检查热点名称、密码、HTTPS 地址、访问密钥和会话 ID");return;
    }
    prefs.putString("ssid",ssid);prefs.putString("wifi_pass",pass);prefs.putString("server",url);
    prefs.putString("token",token);prefs.putString("session",session);
    wifiSsid=ssid;wifiPassword=pass;serverUrl=url;accessToken=token;sessionId=session;
    portal.send(200,"text/html; charset=utf-8","<meta charset='utf-8'><h2>配置已保存</h2><p>请离开摄像头 Wi-Fi，打开刚填写的手机热点（2.4 GHz）。设备会自动连接并上传；安卓 App 可查看画面连接状态。</p>");
    connectWifi();
  });
  portal.begin();
}

bool initCamera() {
  camera_config_t c={};
  c.ledc_channel=LEDC_CHANNEL_0;c.ledc_timer=LEDC_TIMER_0;
  c.pin_d0=CAM_D0;c.pin_d1=CAM_D1;c.pin_d2=CAM_D2;c.pin_d3=CAM_D3;
  c.pin_d4=CAM_D4;c.pin_d5=CAM_D5;c.pin_d6=CAM_D6;c.pin_d7=CAM_D7;
  c.pin_xclk=CAM_XCLK;c.pin_pclk=CAM_PCLK;c.pin_vsync=CAM_VSYNC;c.pin_href=CAM_HREF;
  c.pin_sccb_sda=CAM_SDA;c.pin_sccb_scl=CAM_SCL;c.pin_pwdn=-1;c.pin_reset=-1;
  c.xclk_freq_hz=15000000;c.pixel_format=PIXFORMAT_JPEG;c.frame_size=FRAMESIZE_VGA;
  c.jpeg_quality=14;c.fb_count=psramFound()?2:1;
  c.fb_location=psramFound()?CAMERA_FB_IN_PSRAM:CAMERA_FB_IN_DRAM;
  c.grab_mode=CAMERA_GRAB_LATEST;
  if(!psramFound()){c.frame_size=FRAMESIZE_QVGA;Serial.println("No PSRAM; fallback to 320x240");}
  esp_err_t err=esp_camera_init(&c);
  if(err!=ESP_OK){Serial.printf("Camera init failed: 0x%x. Check connector, board pins, PSRAM.\n",err);return false;}
  sensor_t *s=esp_camera_sensor_get();
  Serial.printf("Camera sensor PID=0x%04x, JPEG enabled\n",s->id.PID);
  return true;
}

void uploadFrame() {
  if(!cameraReady || serverUrl.isEmpty() || WiFi.status()!=WL_CONNECTED)return;
  if(serverUrl.startsWith("https://") && time(nullptr)<1700000000){Serial.println("Waiting for NTP before TLS");return;}
  camera_fb_t *fb=esp_camera_fb_get();
  if(!fb){Serial.println("Camera capture failed");return;}
  String url=serverUrl+"/v1/frame/"+sessionId;
  bool ok=true;
  if(uplinkUrl!=url){
    uplink.end();
    if(serverUrl.startsWith("https://")){tlsUplink.setCACert(JARVIS_ROOT_CA);tlsUplink.setHandshakeTimeout(12);ok=uplink.begin(tlsUplink,url);}
    else ok=uplink.begin(plainUplink,url);
    if(ok){uplinkUrl=url;uplink.setReuse(true);uplink.setTimeout(5000);uplink.setConnectTimeout(5000);}
  }
  if(ok){
    uplink.addHeader("Authorization","Bearer "+accessToken);uplink.addHeader("Content-Type","image/jpeg");
    uplink.addHeader("X-Frame-Sequence",String(uploadSequence++));
    lastHttpStatus=uplink.POST(fb->buf,fb->len);
    if(lastHttpStatus>0)uplink.getString(); // drain small JSON response so the TLS connection can be reused
    if(lastHttpStatus!=200)Serial.printf("Frame upload status=%d bytes=%u\n",lastHttpStatus,(unsigned)fb->len);
    else if(uploadSequence%30==0)Serial.printf("Uploaded %u frames, free heap=%u\n",uploadSequence,ESP.getFreeHeap());
    if(lastHttpStatus!=200){retryInterval=min((uint32_t)16000,retryInterval*2);uplink.end();uplinkUrl="";}
    else retryInterval=FRAME_INTERVAL_MS;
  }
  esp_camera_fb_return(fb);
}

void setup() {
  Serial.begin(115200);delay(700);pinMode(0,INPUT_PULLUP);
  Serial.printf("Jarvis Camera 0.1.0 | flash=%u PSRAM=%u\n",ESP.getFlashChipSize(),ESP.getPsramSize());
  prefs.begin("jarvis",false);
  wifiSsid=prefs.getString("ssid","");wifiPassword=prefs.getString("wifi_pass","");
  serverUrl=prefs.getString("server","");accessToken=prefs.getString("token","");sessionId=prefs.getString("session","jarvis");
  cameraReady=initCamera();WiFi.mode(WIFI_STA);WiFi.setSleep(false);WiFi.setAutoReconnect(true);
  initPortal();configTime(0,0,"ntp.aliyun.com","pool.ntp.org","time.cloudflare.com");
  if(wifiSsid.isEmpty())startPortal();else connectWifi();
}

void loop() {
  portal.handleClient();
  static uint32_t pressed=0, disconnectedSince=0;
  static bool buttonHandled=false;
  if(digitalRead(0)==LOW){if(!pressed)pressed=millis();if(!buttonHandled && millis()-pressed>3000){startPortal();buttonHandled=true;}}
  else {pressed=0;buttonHandled=false;}
  if(WiFi.status()==WL_CONNECTED){
    disconnectedSince=0;
    automaticPortalOpened=false;
    if(!connectedOnce){connectedOnce=true;Serial.printf("Hotspot connected: %s\n",WiFi.localIP().toString().c_str());}
    if(millis()-lastUpload>=retryInterval){lastUpload=millis();uploadFrame();}
  }else{
    connectedOnce=false;
    if(!disconnectedSince)disconnectedSince=millis();
    if(!portalOpen && !automaticPortalOpened && millis()-disconnectedSince>45000)startPortal();
    if(millis()-lastConnectAttempt>30000)connectWifi();
  }
  if(portalOpen && millis()-portalStarted>SETUP_WINDOW_MS){WiFi.softAPdisconnect(true);portalOpen=false;Serial.println("Setup window closed; hold BOOT to reopen");}
  delay(5);
}
