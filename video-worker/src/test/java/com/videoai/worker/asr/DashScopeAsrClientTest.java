package com.videoai.worker.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeAsrClientTest {
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
        var client = new DashScopeAsrClient(properties, new DashScopeConfig(), json, http);
        assertEquals("test-id", client.submit("https://audio.example/source.wav"));
        var query = client.query("test-id");
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
        var client = new DashScopeAsrClient(props, new DashScopeConfig(), json, http);
        assertThrows(IOException.class, () -> client.query("id"));
        assertThrows(IOException.class, () -> DashScopeAsrClient.normalize(json.readTree("{}"), 0, 0, 1000));
        assertThrows(IOException.class, () -> DashScopeAsrClient.normalize(json.readTree(
                "{\"transcripts\":[{\"sentences\":[{\"begin_time\":0,\"end_time\":2000,\"text\":\"x\"}]}]}"), 0, 0, 1000));
        assertEquals(0, DashScopeAsrClient.normalize(json.readTree("{\"transcripts\":[]}"), 0, 0, 1000).utterances().size());
    }
}
