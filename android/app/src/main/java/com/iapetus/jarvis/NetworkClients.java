package com.iapetus.jarvis;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class NetworkClients {
    private NetworkClients() {}
    static Dns ipv4First(Dns upstream) {
        return host -> {
            List<InetAddress> addresses = new ArrayList<>(upstream.lookup(host));
            // Keep IPv6 as fallback. Some mobile networks advertise an unusable IPv6 route.
            addresses.sort((a,b) -> Integer.compare(a instanceof Inet4Address ? 0 : 1,
                                                     b instanceof Inet4Address ? 0 : 1));
            return addresses;
        };
    }
    private static OkHttpClient.Builder base() {
        return new OkHttpClient.Builder().dns(ipv4First(Dns.SYSTEM))
            .connectTimeout(7,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS);
    }
    static OkHttpClient status() {
        return base().readTimeout(7,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build();
    }
    static OkHttpClient requests() {
        return base().readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build();
    }
    static OkHttpClient live() {
        return base().readTimeout(0,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build();
    }
}
