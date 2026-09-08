package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeTextClientTest {
    @Test void readAndOverallTimeoutBothFollowConfiguredLimit() {
        var config=new TextAnalysisProperties();config.setTimeoutSeconds(120);
        var client=new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper());
        var http=(OkHttpClient)org.springframework.test.util.ReflectionTestUtils.getField(client,"http");
        assertNotNull(http);assertEquals(120000,http.readTimeoutMillis());assertEquals(120000,http.callTimeoutMillis());
    }
    @Test void sendsOnlyTextAndReturnsRawResponseForPersistence() throws Exception {
        var config=new TextAnalysisProperties();config.setApiKey("test-key");var json=new ObjectMapper();
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            var request=chain.request();assertEquals("Bearer test-key",request.header("Authorization"));
            var buffer=new okio.Buffer();request.body().writeTo(buffer);var payload=json.readTree(buffer.readUtf8());
            assertFalse(payload.has("video"));assertEquals("user data",payload.path("messages").get(1).path("content").asText());
            assertEquals(4096,payload.path("max_tokens").asInt());assertFalse(payload.path("enable_thinking").asBoolean());
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"),"invalid business response")).build();
        }).build();
        var client=new DashScopeTextClient(config,new DashScopeConfig(),json,http);
        assertEquals("invalid business response",client.complete("system","user data"));
    }
    @Test void rejectsCredentialRedirectionAndDoesNotRetryHttpFailure() throws Exception {
        var config=new TextAnalysisProperties();config.setApiKey("test-key");var count=new AtomicInteger();
        var http=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{count.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("limited")
                    .body(ResponseBody.create(MediaType.parse("application/json"),"{}")).build();}).build();
        var client=new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper(),http);
        config.setBaseUrl("https://untrusted.example");assertThrows(IOException.class,()->client.complete("s","u"));assertEquals(0,count.get());
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThrows(IOException.class,()->client.complete("s","u"));assertEquals(1,count.get());
    }
}
