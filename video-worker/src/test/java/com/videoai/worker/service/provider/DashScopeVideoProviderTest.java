package com.videoai.worker.service.provider;

import com.videoai.worker.config.DashScopeConfig;
import org.junit.jupiter.api.Test;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeVideoProviderTest {
    private final AiCallRecorder recorder=mock(AiCallRecorder.class);
    private final AiCallContext context=new AiCallContext("task",0,AiCallContext.Stage.VIDEO_ANALYSIS,0);
    @Test void errorRetainsVendorCodeWithoutSignedUrl() {
        var config = new DashScopeConfig(); config.setApiKey("test");
        var provider = new DashScopeVideoProvider(config,recorder,new ObjectMapper()) {
            @Override protected HttpReceipt invoke(VideoRequest param) {
                return new HttpReceipt(400,"{\"id\":\"request-1\",\"error\":{\"code\":\"InvalidParameter.DataInspection\",\"message\":\"download https://example.com/video?Signature=SECRET failed\"}}");
            }
        };
        var error = assertThrows(AiProviderException.class, () -> provider.callDetailed(context,"https://example.com/video", "test"));
        assertTrue(error.getMessage().contains("InvalidParameter.DataInspection"));
        assertTrue(error.getMessage().contains("request-1"));
        assertFalse(error.getMessage().contains("SECRET"));
        assertFalse(error.isRetryable());
    }
    @Test void concurrentRequestsUseIndependentBodiesAndTimeouts() throws Exception {
        var config = new DashScopeConfig(); config.setApiKey("test");


        Set<DashScopeVideoProvider.VideoRequest> params = Collections.newSetFromMap(new IdentityHashMap<>());
        var barrier = new CyclicBarrier(2);
        var provider = new DashScopeVideoProvider(config,recorder,new ObjectMapper()) {
            @Override protected HttpReceipt invoke(VideoRequest param) throws Exception {
                synchronized (params) { params.add(param); }
                assertEquals(Duration.ofSeconds(300), param.timeout());
                var body = new ObjectMapper().readTree(param.body()); assertEquals(4096, body.path("max_tokens").asInt()); assertFalse(body.path("enable_thinking").asBoolean());
                barrier.await(2, TimeUnit.SECONDS);
                return new HttpReceipt(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}");
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> provider.callDetailed(context,"https://a.example/video", "A"));
            var b = pool.submit(() -> provider.callDetailed(context,"https://b.example/video", "B"));
            assertEquals("ok", a.get().text()); assertEquals("stop", b.get().finishReason());
        } finally { pool.shutdownNow(); }
        assertEquals(2, params.size());
        assertFalse(provider.segmentSettings().toString().contains("apiKey"));
    }

    private DashScopeVideoProvider httpProvider(int status,String body,java.util.concurrent.atomic.AtomicInteger calls) {
        var config=new DashScopeConfig();config.setApiKey("test-key");
        var http=new okhttp3.OkHttpClient.Builder().addInterceptor(chain->{
            calls.incrementAndGet();
            verify(recorder).begin(anyString(),eq(context),eq(config.getModel()));
            var request=chain.request();assertEquals("Bearer test-key",request.header("Authorization"));
            assertEquals("/compatible-mode/v1/chat/completions",request.url().encodedPath());
            var buffer=new okio.Buffer();request.body().writeTo(buffer);
            var payload=new ObjectMapper().readTree(buffer.readUtf8());
            assertEquals("qwen3.7-plus",payload.path("model").asText());
            assertEquals(4096,payload.path("max_tokens").asInt());
            assertFalse(payload.path("enable_thinking").asBoolean());
            assertEquals("system",payload.path("messages").path(0).path("role").asText());
            assertEquals(DashScopeVideoProvider.SYSTEM_PROMPT,payload.path("messages").path(0).path("content").asText());
            assertTrue(DashScopeVideoProvider.SYSTEM_PROMPT.contains("左上角是地图"));
            assertTrue(DashScopeVideoProvider.SYSTEM_PROMPT.contains("顶级复盘教练"));
            assertEquals("user",payload.path("messages").path(1).path("role").asText());
            var content=payload.path("messages").path(1).path("content");
            assertEquals("video_url",content.path(0).path("type").asText());assertEquals(2,content.path(0).path("fps").asInt());assertEquals("https://example.com/video",content.path(0).path("video_url").path("url").asText());
            assertFalse(payload.has("api_key"));
            return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(status).message("mock").body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"),body)).build();
        }).build();
        return new DashScopeVideoProvider(config,recorder,new ObjectMapper(),http);
    }

    @Test void invalidChoicesStillRecordsUsageBeforeParsing() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=httpProvider(200,"{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20},\"id\":\"r1\"}",calls);
        assertThrows(AiProviderException.class,()->provider.callDetailed(context,"https://example.com/video","p"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.SUCCEEDED),
                argThat(u->Long.valueOf(100).equals(u.inputTokens()) && Long.valueOf(20).equals(u.outputTokens())),eq("r1"),isNull());
        assertEquals(1,calls.get());
    }

    @Test void errorUsageIsRecordedAndNoHiddenRetryOccurs() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=httpProvider(503,"{\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":0}}",calls);
        var e=assertThrows(AiProviderException.class,()->provider.callDetailed(context,"https://example.com/video","p"));
        assertTrue(e.isRetryable());assertEquals(1,calls.get());
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.FAILED),argThat(u->Long.valueOf(17).equals(u.inputTokens())),isNull(),eq("HTTP_503"));
    }

    @Test void beginFailurePreventsNetwork() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var provider=httpProvider(200,"{}",calls);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(recorder).begin(anyString(),any(),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->provider.callDetailed(context,"https://example.com/video","p"));
        assertEquals(0,calls.get());verify(recorder,never()).finish(anyString(),any(),any(),any(),any());
    }

    @Test void finishingFailureDoesNotDiscardSuccessfulResponse() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=httpProvider(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}",calls);
        when(recorder.finish(anyString(),any(),any(),any(),any())).thenReturn(false);
        assertEquals("ok",provider.callDetailed(context,"https://example.com/video","p").text());assertEquals(1,calls.get());
    }

    @Test void cancelledAfterBeginDoesNotSend() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var provider=httpProvider(200,"{}",calls);
        doAnswer(i->{Thread.currentThread().interrupt();return i.getArgument(0);}).when(recorder).begin(anyString(),any(),anyString());
        try {
            assertThrows(AiProviderException.class,()->provider.callDetailed(context,"https://example.com/video","p"));
            verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.NOT_SENT),eq(AiUsage.unknown()),isNull(),eq("CANCELLED_BEFORE_SEND"));
            assertEquals(0,calls.get());assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
}
