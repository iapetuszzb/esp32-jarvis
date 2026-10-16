#pragma once
#include <lwip/sockets.h>
#include <fcntl.h>
#include <errno.h>
#include <netinet/tcp.h>
#include <mbedtls/gcm.h>
#include <mbedtls/sha256.h>
#include <esp_timer.h>
#include <sys/time.h>

// JCU1 packets stay below the typical 1500-byte Wi-Fi MTU, including IP/UDP headers.
class CameraUdp {
  int udpSocket=-1,tcpSocket=-1;
  uint32_t lastTcpProbe=0;
  bool tcpProbed=false,usedTcp=false;
  uint8_t boot[12], key[32];
  uint32_t sequence=0;
  bool ready=false;
  static void put16(uint8_t *p,uint16_t n){p[0]=n>>8;p[1]=n;}
  static void put32(uint8_t *p,uint32_t n){for(int i=3;i>=0;i--){p[i]=n;n>>=8;}}
  static void put64(uint8_t *p,uint64_t n){for(int i=7;i>=0;i--){p[i]=n;n>>=8;}}
  void closeTcp(){if(tcpSocket>=0)::close(tcpSocket);tcpSocket=-1;}
  bool tcpWrite(const uint8_t *bytes,size_t size,uint32_t started){
    size_t offset=0;
    while(offset<size&&millis()-started<800&&WiFi.status()==WL_CONNECTED){
      int n=::send(tcpSocket,bytes+offset,size-offset,0);
      if(n>0)offset+=n;
      else if(n<0&&(errno==ENOMEM||errno==EAGAIN||errno==ENOBUFS))delay(5);
      else return false;
    }
    return offset==size;
  }
  bool connectTcp(IPAddress target){
    if(tcpSocket>=0)return true;
    if(tcpProbed&&millis()-lastTcpProbe<5000)return false;
    tcpProbed=true;lastTcpProbe=millis();
    tcpSocket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(tcpSocket<0)return false;
    fcntl(tcpSocket,F_SETFL,O_NONBLOCK);
    sockaddr_in dest={};dest.sin_family=AF_INET;dest.sin_port=htons(19422);dest.sin_addr.s_addr=(uint32_t)target;
    int result=::connect(tcpSocket,(sockaddr*)&dest,sizeof(dest));
    if(result<0&&errno==EINPROGRESS){
      fd_set writes;FD_ZERO(&writes);FD_SET(tcpSocket,&writes);timeval timeout={0,350000};
      result=::select(tcpSocket+1,nullptr,&writes,nullptr,&timeout);
      int error=0;socklen_t length=sizeof(error);
      if(result>0&&getsockopt(tcpSocket,SOL_SOCKET,SO_ERROR,&error,&length)==0&&error==0)result=0;
      else result=-1;
    }
    if(result!=0){closeTcp();return false;}
    int yes=1;setsockopt(tcpSocket,IPPROTO_TCP,TCP_NODELAY,&yes,sizeof(yes));
    Serial.printf("Camera relay TCP connected: %s:19422\n",target.toString().c_str());
    return true;
  }
public:
  void reset(){closeTcp();if(udpSocket>=0)::close(udpSocket);udpSocket=-1;ready=false;tcpProbed=false;}
  const char *lastTransport()const{return usedTcp?"TCP":"UDP";}
  bool send(camera_fb_t *fb,const String &token,const String &session){
    constexpr size_t CHUNK=1120,HEADER=40;
    if(fb->len>256*1024||fb->len<4)return false;
    if(!ready){
      String material="jarvis-camera-udp-v1\n"+session+"\n"+token;
      if(mbedtls_sha256_ret((const unsigned char*)material.c_str(),material.length(),key,0)!=0)return false;
      esp_fill_random(boot,sizeof(boot));sequence=0;
      ready=true;
    }
    if(sequence==UINT32_MAX){esp_fill_random(boot,sizeof(boot));sequence=0;}
    uint32_t frame=sequence++;
    timeval now;gettimeofday(&now,nullptr);
    int64_t ageUs=esp_timer_get_time()-((int64_t)fb->timestamp.tv_sec*1000000+fb->timestamp.tv_usec);
    if(ageUs<0)ageUs=0;
    if(ageUs>1500000)return false; // stale DMA frame after a long pause is dropped, never queued
    uint64_t captured=(uint64_t)now.tv_sec*1000+now.tv_usec/1000-ageUs/1000;
    uint16_t count=(fb->len+CHUNK-1)/CHUNK;
    IPAddress target=WiFi.gatewayIP();
    usedTcp=connectTcp(target);
    if(!usedTcp&&udpSocket<0){
      udpSocket=::socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
      if(udpSocket<0)return false;
      fcntl(udpSocket,F_SETFL,O_NONBLOCK);
    }
    sockaddr_in destination={};destination.sin_family=AF_INET;destination.sin_port=htons(19421);
    destination.sin_addr.s_addr=(uint32_t)target;
    uint8_t packet[HEADER+CHUNK+16],digest[32];
    memcpy(packet,"JCU1",4);memcpy(packet+4,boot,12);put32(packet+16,frame);
    put32(packet+20,fb->len);put16(packet+26,count);put64(packet+28,captured);
    // Bytes 36..39 are reserved and authenticated for future protocol extension.
    memset(packet+36,0,4);
    mbedtls_gcm_context cipher;mbedtls_gcm_init(&cipher);
    bool ok=mbedtls_gcm_setkey(&cipher,MBEDTLS_CIPHER_ID_AES,key,256)==0;
    uint32_t started=millis();
    for(uint16_t index=0;ok&&index<count;index++){
      if(millis()-started>800||WiFi.status()!=WL_CONNECTED){ok=false;break;}
      put16(packet+24,index);
      size_t offset=(size_t)index*CHUNK,len=min(CHUNK,fb->len-offset);
      ok=mbedtls_sha256_ret(packet,HEADER,digest,0)==0;
      if(ok)ok=mbedtls_gcm_crypt_and_tag(&cipher,MBEDTLS_GCM_ENCRYPT,len,digest,12,packet,HEADER,
          fb->buf+offset,packet+HEADER,16,packet+HEADER+len)==0;
      if(ok){
        if(usedTcp){
          uint8_t prefix[2];put16(prefix,HEADER+len+16);
          ok=tcpWrite(prefix,sizeof(prefix),started)&&tcpWrite(packet,HEADER+len+16,started);
          if(!ok)Serial.printf("TCP TX failed fragment %u/%u errno=%d\n",index,count,errno);
          continue;
        }
        // Back off briefly if the Wi-Fi TX pool is full; never queue/re-encrypt an old frame.
        bool sent=false;
        while(!sent&&millis()-started<800){
          sent=::sendto(udpSocket,packet,HEADER+len+16,0,(sockaddr*)&destination,sizeof(destination))==(int)(HEADER+len+16);
          if(!sent){
            if(errno!=ENOMEM&&errno!=EAGAIN&&errno!=ENOBUFS)break;
            delay(20);
          }
        }
        ok=sent;
        if(!ok){
          Serial.printf("UDP TX failed fragment %u/%u errno=%d target=%s\n",index,count,errno,target.toString().c_str());
        }
      }
      delay(20); // allow the driver to drain its bounded TX buffers between packets
    }
    mbedtls_gcm_free(&cipher);
    if(usedTcp&&ok){
      // One authenticated frame at a time. Do not let TCP queue old JPEGs.
      uint8_t ack[8],expected[8];memcpy(expected,"JCA1",4);put32(expected+4,frame);size_t got=0;
      while(got<sizeof(ack)&&millis()-started<800){
        int n=::recv(tcpSocket,ack+got,sizeof(ack)-got,0);
        if(n>0)got+=n;
        else if(n<0&&errno==EAGAIN)delay(5);
        else break;
      }
      ok=got==sizeof(ack)&&memcmp(ack,expected,sizeof(ack))==0;
      if(!ok)Serial.println("TCP relay frame ACK timeout/invalid; discard connection and old frame");
    }
    if(usedTcp&&!ok)closeTcp();
    return ok;
  }
};
