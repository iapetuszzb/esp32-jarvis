package com.iapetus.jarvis;

import okhttp3.*;
import java.io.IOException;

/** One pending status call. Cancellation also suppresses obsolete callbacks. */
final class StatusPoller {
    private final OkHttpClient client;
    private Call pending;
    StatusPoller(OkHttpClient client) { this.client=client; }
    synchronized boolean start(Request request,Callback callback) {
        if(pending!=null)return false;
        Call next=client.newCall(request);pending=next;
        next.enqueue(new Callback(){
            public void onFailure(Call call,IOException error) {
                synchronized(StatusPoller.this){
                    if(pending!=call)return;
                    pending=null;
                }
                callback.onFailure(call,error);
            }
            public void onResponse(Call call,Response response)throws IOException {
                synchronized(StatusPoller.this){
                    if(pending!=call){response.close();return;}
                }
                try{callback.onResponse(call,response);}
                finally{synchronized(StatusPoller.this){if(pending==call)pending=null;}}
            }
        });
        return true;
    }
    synchronized void cancel() { if(pending!=null){pending.cancel();pending=null;} }
}
