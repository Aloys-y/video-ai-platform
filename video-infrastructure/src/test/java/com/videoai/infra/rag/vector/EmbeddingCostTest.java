package com.videoai.infra.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import okhttp3.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingCostTest {
    final AiCallRecorder recorder=mock(AiCallRecorder.class);
    final AiCallContext context=new AiCallContext("video",3,AiCallContext.Stage.RAG_EMBEDDING,0);
    final AtomicInteger calls=new AtomicInteger();
    EmbeddingProvider client(boolean compatible,int status,String body) {
        var properties=new OpenAiEmbeddingProperties();properties.setApiKey("test-key");
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            calls.incrementAndGet();assertTrue(chain.request().body().isOneShot());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("mock")
                    .body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
        }).build();
        return compatible?new OpenAiCompatibleEmbeddingProvider(properties,new ObjectMapper(),recorder,http)
                :new DashScopeEmbeddingProvider(properties,new ObjectMapper(),recorder,http);
    }
    String valid(boolean compatible) {
        return compatible?"{\"usage\":{\"total_tokens\":100},\"data\":[{\"embedding\":[0.1,0.2]}]}"
                :"{\"usage\":{\"total_tokens\":100},\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2]}]}}";
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void successfulUsageIsRecordedEvenWhenVectorIsInvalid(boolean compatible) {
        var client=client(compatible,200,"{\"usage\":{\"total_tokens\":100}}");
        assertThrows(IllegalStateException.class,()->client.embedQuery(context,"q"));
        verify(recorder).begin(anyString(),eq(context),eq("text-embedding-v3"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.SUCCEEDED),argThat(u->Long.valueOf(100).equals(u.inputTokens()) && u.outputTokens()==null),isNull(),isNull());
        assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void offlineDocumentAndEvaluationAreNotChargedToVideo(boolean compatible) {
        var client=client(compatible,200,valid(compatible));
        assertEquals(2,client.embedDocument("document").size());assertEquals(2,client.embedQuery("offline").size());
        verifyNoInteractions(recorder);assertEquals(2,calls.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void registrationFailurePreventsNetwork(boolean compatible) {
        var client=client(compatible,200,valid(compatible));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(recorder).begin(anyString(),any(),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->client.embedQuery(context,"q"));assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void failedReceiptAndFailedBookkeepingNeverRepeatModelCall(boolean compatible) {
        var failed=client(compatible,503,"{\"usage\":{\"input_tokens\":17}}");
        assertThrows(IllegalStateException.class,()->failed.embedQuery(context,"q"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.FAILED),argThat(u->Long.valueOf(17).equals(u.inputTokens())),isNull(),eq("HTTP_503"));
        var success=client(compatible,200,valid(compatible));
        when(recorder.finish(anyString(),any(),any(),any(),any())).thenReturn(false);
        assertEquals(2,success.embedQuery(context,"q").size());assertEquals(2,calls.get());
    }
}
