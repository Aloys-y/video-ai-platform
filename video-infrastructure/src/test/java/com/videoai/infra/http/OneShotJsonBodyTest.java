package com.videoai.infra.http;

import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OneShotJsonBodyTest {
    @Test void retryAfterZeroCannotReplayPaidPost() throws Exception {
        var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();count.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After","0");
            byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        var client=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).callTimeout(java.time.Duration.ofSeconds(5)).build();
        try {
            var request=new Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/")
                    .post(new OneShotJsonBody("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).build();
            try(var response=client.newCall(request).execute()) {assertEquals(503,response.code());}
            assertEquals(1,count.get());
        } finally {server.stop(0);client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();}
    }
}
