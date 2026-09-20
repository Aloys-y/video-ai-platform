package com.videoai.worker.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import static org.mockito.Mockito.*;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeAsrClientTest {
    private final AiCallContext context = new AiCallContext("task",0,AiCallContext.Stage.ASR,0);
    private final AiCallRecorder recorder = mock(AiCallRecorder.class);
    @org.junit.jupiter.api.BeforeEach void ledger() { when(recorder.findAsrCall(any(),anyString())).thenReturn(UUID.randomUUID().toString()); }
    private final ObjectMapper json = new ObjectMapper();

    @Test void usesApiAuthButNeverLeaksItToResultDownload() throws Exception {
        List<Request> requests = new ArrayList<>();
        var http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request(); requests.add(request);
            String body = request.method().equals("POST") ? "{\"output\":{\"task_id\":\"test-id\"}}"
                    : request.url().encodedPath().startsWith("/api/")
                    ? "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://results.example/asr.json?signature=private\"}]}}"
                    : "{\"file_url\":\"https://private.example/audio\",\"transcripts\":[{\"sentences\":[{\"begin_time\":100,\"end_time\":900,\"text\":\"前面有人\"}]}]}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
        }).build();
        var properties = new AsrProperties(); properties.setApiKey("test-key");
        var client = new DashScopeAsrClient(properties, new DashScopeConfig(), json, recorder, http);
        assertEquals("test-id", client.submit(context,"https://audio.example/source.wav"));
        var query = client.query(context,"test-id");
        var transcript = client.downloadResult(query.resultUrl(), 2, 10000, 12000);
        assertEquals(10100, transcript.utterances().get(0).startMs());
        assertEquals("p2-u0", transcript.utterances().get(0).id());
        assertEquals("Bearer test-key", requests.get(0).header("Authorization"));
        assertEquals("enable", requests.get(0).header("X-DashScope-Async"));
        assertNull(requests.get(2).header("Authorization")); assertNull(requests.get(2).header("Content-Type"));
        assertFalse(transcript.sanitizedResponse().toString().contains("private.example"));
    }

    @Test void failedSubtaskAndInvalidTimestampsAreNotEmptySuccess() throws Exception {
        var http = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.parse("application/json"),
                        "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"subtask_status\":\"FAILED\"}]}}"))
                .build()).build();
        var props = new AsrProperties(); props.setApiKey("test-key");
        var client = new DashScopeAsrClient(props, new DashScopeConfig(), json, recorder, http);
        assertThrows(IOException.class, () -> client.query(context,"id"));
        assertThrows(IOException.class, () -> DashScopeAsrClient.normalize(json.readTree("{}"), 0, 0, 1000));
        assertThrows(IOException.class, () -> DashScopeAsrClient.normalize(json.readTree(
                "{\"transcripts\":[{\"sentences\":[{\"begin_time\":0,\"end_time\":2000,\"text\":\"x\"}]}]}"), 0, 0, 1000));
        assertEquals(0, DashScopeAsrClient.normalize(json.readTree("{\"transcripts\":[]}"), 0, 0, 1000).utterances().size());
    }

    private DashScopeAsrClient mockResponse(int status,String body,java.util.concurrent.atomic.AtomicInteger count) {
        var props=new AsrProperties();props.setApiKey("test-key");
        var http=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{
            count.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("mock").body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
        }).build();
        return new DashScopeAsrClient(props,new DashScopeConfig(),json,recorder,http);
    }

    @Test void submissionMustBeRegisteredAndOnlyAcceptedRemainsRunning() throws Exception {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var client=mockResponse(200,"{\"request_id\":\"submit-r1\",\"output\":{\"task_id\":\"remote\"}}",count);
        assertEquals("remote",client.submit(context,"https://audio.example/audio.wav"));
        var order=inOrder(recorder);order.verify(recorder).begin(anyString(),eq(context),anyString());
        order.verify(recorder).bindRemoteTask(anyString(),eq("remote"),eq("submit-r1"));
        verify(recorder,never()).finish(anyString(),any(),any(),any(),any());assertEquals(1,count.get());
    }

    @Test void failedSubmissionRetainsUsageAndDoesNotRetry() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var client=mockResponse(400,"{\"usage\":{\"duration\":12}}",count);
        assertThrows(IOException.class,()->client.submit(context,"https://audio.example/audio.wav"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.FAILED),
                argThat(u->new java.math.BigDecimal("12").equals(u.audioSeconds())),isNull(),anyString());
        assertEquals(1,count.get());
    }

    @Test void ledgerFailurePreventsSubmissionAndMissingOriginPreventsPoll() {
        var count=new java.util.concurrent.atomic.AtomicInteger();var client=mockResponse(200,"{}",count);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(recorder).begin(anyString(),any(),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->client.submit(context,"https://audio.example/audio.wav"));
        doThrow(new IllegalStateException("missing origin")).when(recorder).findAsrCall(any(),anyString());
        assertThrows(IllegalStateException.class,()->client.query(context,"remote"));assertEquals(0,count.get());
    }

    @Test void terminalUsageIsRecordedBeforeRejectingInvalidTranscriptionResult() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var client=mockResponse(200,"{\"request_id\":\"poll-new-id\",\"usage\":{\"duration\":15},\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[]}}",count);
        assertThrows(IOException.class,()->client.query(context,"remote"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.SUCCEEDED),
                argThat(u->new java.math.BigDecimal("15").equals(u.audioSeconds())),isNull(),isNull());
        verify(recorder,never()).begin(anyString(),any(),anyString());
    }

    @Test void submissionCancellationAfterBeginIsNotSent() {
        var count=new java.util.concurrent.atomic.AtomicInteger();var client=mockResponse(200,"{}",count);
        doAnswer(i->{Thread.currentThread().interrupt();return i.getArgument(0);}).when(recorder).begin(anyString(),any(),anyString());
        try {
            assertThrows(IOException.class,()->client.submit(context,"https://audio.example/audio.wav"));
            verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.NOT_SENT),eq(AiUsage.unknown()),isNull(),anyString());
            assertEquals(0,count.get());assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
}
