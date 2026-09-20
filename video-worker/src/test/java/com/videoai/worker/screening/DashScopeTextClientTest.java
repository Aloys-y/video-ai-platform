package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import static org.mockito.Mockito.*;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeTextClientTest {
    private final AiCallRecorder recorder = mock(AiCallRecorder.class);
    private final AiCallContext context = new AiCallContext("task", 2, AiCallContext.Stage.TEXT_SCREEN, 3);
    @Test void readAndOverallTimeoutBothFollowConfiguredLimit() {
        var config=new TextAnalysisProperties();config.setTimeoutSeconds(120);
        var client=new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper(),recorder);
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
        var client=new DashScopeTextClient(config,new DashScopeConfig(),json,recorder,http);
        assertEquals("invalid business response",client.complete(context,"system","user data"));
    }
    @Test void rejectsCredentialRedirectionAndDoesNotRetryHttpFailure() throws Exception {
        var config=new TextAnalysisProperties();config.setApiKey("test-key");var count=new AtomicInteger();
        var http=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{count.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("limited")
                    .body(ResponseBody.create(MediaType.parse("application/json"),"{}")).build();}).build();
        var client=new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper(),recorder,http);
        config.setBaseUrl("https://untrusted.example");assertThrows(IOException.class,()->client.complete(context,"s","u"));assertEquals(0,count.get());
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThrows(IOException.class,()->client.complete(context,"s","u"));assertEquals(1,count.get());
    }

    private DashScopeTextClient responseClient(int status, String body, AtomicInteger calls) {
        var config = new TextAnalysisProperties(); config.setApiKey("test-key");
        var http = new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain -> {
            calls.incrementAndGet();
            verify(recorder).begin(anyString(), eq(context), eq(config.getModel()));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("mock").body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
        }).build();
        return new DashScopeTextClient(config, new DashScopeConfig(), new ObjectMapper(), recorder, http);
    }

    @Test void recordsUsageBeforeBusinessParsingEvenWhenContentIsInvalid() throws Exception {
        var count = new AtomicInteger();
        String raw = "{\"id\":\"request-1\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20},\"choices\":[]}";
        assertEquals(raw, responseClient(200, raw, count).complete(context,"s","u"));
        var usage = org.mockito.ArgumentCaptor.forClass(AiUsage.class);
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.SUCCEEDED),usage.capture(),eq("request-1"),isNull());
        assertEquals(100L,usage.getValue().inputTokens()); assertEquals(20L,usage.getValue().outputTokens());
        assertFalse(usage.getValue().rawJson().contains("choices")); assertEquals(1,count.get());
    }

    @Test void recordsErrorReceiptWithoutAssumingFailureIsFree() {
        var count = new AtomicInteger();
        var client = responseClient(429,"{\"usage\":{\"input_tokens\":17,\"output_tokens\":0}}",count);
        assertThrows(IOException.class,()->client.complete(context,"s","u"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.FAILED),
                argThat(u->Long.valueOf(17).equals(u.inputTokens())),isNull(),eq("HTTP_429"));
        assertEquals(1,count.get());
    }

    @Test void registrationFailurePreventsSending() {
        var count = new AtomicInteger(); var client = responseClient(200,"{}",count);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"))
                .when(recorder).begin(anyString(),any(),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->client.complete(context,"s","u"));
        assertEquals(0,count.get()); verify(recorder,never()).finish(anyString(),any(),any(),any(),any());
    }

    @Test void failedBookkeepingDoesNotDiscardResponseOrRepeatRequest() throws Exception {
        var count = new AtomicInteger(); var client = responseClient(200,"{}",count);
        when(recorder.finish(anyString(),any(),any(),any(),any())).thenReturn(false);
        assertEquals("{}",client.complete(context,"s","u")); assertEquals(1,count.get());
    }

    @Test void timeoutLeavesUnknownCostAndDoesNotRetry() {
        var count = new AtomicInteger(); var config = new TextAnalysisProperties();config.setApiKey("test-key");
        var http = new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{
            count.incrementAndGet();throw new java.net.SocketTimeoutException();
        }).build();
        var client = new DashScopeTextClient(config,new DashScopeConfig(),new ObjectMapper(),recorder,http);
        assertThrows(IOException.class,()->client.complete(context,"s","u"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.UNKNOWN),eq(AiUsage.unknown()),isNull(),anyString());
        assertEquals(1,count.get());
    }

    @Test void cancellationAfterRegistrationIsKnownNotSent() {
        var count = new AtomicInteger(); var client = responseClient(200,"{}",count);
        doAnswer(i->{Thread.currentThread().interrupt();return i.getArgument(0);})
                .when(recorder).begin(anyString(),any(),anyString());
        try {
            assertThrows(IOException.class,()->client.complete(context,"s","u"));
            verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.NOT_SENT),eq(AiUsage.unknown()),isNull(),eq("CANCELLED_BEFORE_SEND"));
            assertEquals(0,count.get());assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
