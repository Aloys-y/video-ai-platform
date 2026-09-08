package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.TranscriptUtterance;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.media.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TextScreeningServiceTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private TextScreeningService service;private AiTextClient client;private AnalysisTaskMapper tasks;
    private AnalysisExecution execution;private final Map<String,byte[]> objects=new HashMap<>();
    private final Map<Integer,AnalysisTextCall> records=new HashMap<>();
    @BeforeEach void setup() throws Exception {
        var config=new TextAnalysisProperties();var props=new MediaProperties();props.setTempRoot(root.toString());props.setMinFreeBytes(0);
        client=mock(AiTextClient.class);tasks=mock(AnalysisTaskMapper.class);
        var executions=mock(AnalysisExecutionMapper.class);var calls=mock(AnalysisTextCallMapper.class);var storage=mock(StorageService.class);
        execution=new AnalysisExecution();execution.setTaskId("task");execution.setExecutionNo(0);execution.setConfigHash("a".repeat(64));
        execution.setConfigSnapshot(json.writeValueAsString(Map.of("settings",Map.of("text",config.snapshot()))));execution.setTranscriptObjectKey("transcript");
        objects.put("transcript",json.writeValueAsBytes(new AudioPrefilterPreparationService.TranscriptManifest(1,10000,"TRANSCRIBED",
                List.of(new TranscriptUtterance("u1",1000,2000,"前面有人",0)))));
        when(tasks.updateStep(anyString(),anyInt(),anyString())).thenReturn(1);
        when(executions.selectExecution(anyString(),anyInt())).thenReturn(execution);
        when(executions.bindOnce(anyString(),anyInt(),any(),anyString())).thenAnswer(i->{execution.setCandidatesObjectKey(i.getArgument(3));return 1;});
        when(calls.select(any())).thenAnswer(i->records.get(((AnalysisTextCall)i.getArgument(0)).getBatchNo()));
        when(calls.claim(any())).thenAnswer(i->{AnalysisTextCall call=i.getArgument(0);records.put(call.getBatchNo(),call);return 1;});
        when(calls.recordResponse(any())).thenReturn(1);
        doAnswer(i->{Files.write((Path)i.getArgument(1),objects.get((String)i.getArgument(0)));return null;})
                .when(storage).downloadToFile(anyString(),any(),anyLong(),any(),anyLong());
        when(storage.putArtifact(any(),anyString())).thenAnswer(i->{String key="artifact/"+UUID.randomUUID();objects.put(key,Files.readAllBytes(i.getArgument(0)));return key;});
        service=new TextScreeningService(config,new CandidatePlanner(config,props,json),client,new MediaPreparationService(props,json),props,
                storage,executions,tasks,calls,json);
    }
    private String response(String content,String finish) throws Exception {
        return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",content))),"usage",Map.of("total_tokens",100)));
    }
    @Test void savesWholeManifestAndReusesWithoutAnotherCall() throws Exception {
        when(client.complete(anyString(),anyString())).thenReturn(response("{\"candidates\":[{\"utterance_ids\":[\"u1\"],\"type\":\"contact\",\"reason\":\"当前接敌\"}]}","stop"));
        var first=service.screen("task",0);var second=service.screen("task",0);
        assertEquals("CANDIDATES",first.outcome());assertEquals(first,second);verify(client,times(1)).complete(anyString(),anyString());
        assertNotNull(records.get(0).getResponseObjectKey());assertEquals(100,first.batches().get(0).usage().path("total_tokens").asInt());
    }
    @Test void invalidOutputRetainsRawResponseAndNeverRepeatsPaidCall() throws Exception {
        when(client.complete(anyString(),anyString())).thenReturn(response("{\"candidates\":[{\"utterance_ids\":[\"fake\"],\"type\":\"contact\",\"reason\":\"x\"}]}","stop"));
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->service.screen("task",0));
        assertNotNull(records.get(0).getResponseObjectKey());assertNull(execution.getCandidatesObjectKey());
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->service.screen("task",0));
        verify(client,times(1)).complete(anyString(),anyString());
    }
    @Test void uncertainCallIsNotAutomaticallyResubmitted() throws Exception {
        when(client.complete(anyString(),anyString())).thenThrow(new IOException("timeout"));
        assertThrows(IOException.class,()->service.screen("task",0));
        var error=assertThrows(IOException.class,()->service.screen("task",0));assertTrue(error.getMessage().contains("结果未知"));
        verify(client,times(1)).complete(anyString(),anyString());
    }
    @Test void emptyTranscriptProducesExplicitZeroWithoutModelCall() throws Exception {
        objects.put("transcript",json.writeValueAsBytes(new AudioPrefilterPreparationService.TranscriptManifest(1,10000,"NO_SPEECH",List.of())));
        assertEquals("NO_CANDIDATES",service.screen("task",0).outcome());verifyNoInteractions(client);
    }
    @Test void truncatedResponseAndStaleExecutionCannotPublishCandidates() throws Exception {
        when(client.complete(anyString(),anyString())).thenReturn(response("{}","length"));
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->service.screen("task",0));
        assertNotNull(records.get(0).getResponseObjectKey());assertNull(execution.getCandidatesObjectKey());
        when(tasks.updateStep(anyString(),anyInt(),anyString())).thenReturn(0);
        assertThrows(IOException.class,()->service.screen("task",0));verify(client,times(1)).complete(anyString(),anyString());
    }
    @Test void summaryRequiresAllFrozenSegmentsAndOrdersEvidence() throws Exception {
        var first=new com.videoai.common.analysis.PreparedSegment(0,0,1000,"first.mp4");
        var second=new com.videoai.common.analysis.PreparedSegment(1,5000,6000,"second.mp4");
        execution.setSegmentsObjectKey("segments");objects.put("segments",json.writeValueAsBytes(
                new AudioPrefilterPreparationService.SegmentManifest(1,List.of(first,second))));
        var a=new com.videoai.common.analysis.SegmentReview(0,0,1000,"first-event",List.of(),List.of());
        var b=new com.videoai.common.analysis.SegmentReview(1,5000,6000,"second-event",List.of(),List.of());
        assertThrows(IOException.class,()->service.summarize("task",0,List.of(a),"知识来源","问题"));
        verifyNoInteractions(client);
        when(client.complete(anyString(),anyString())).thenReturn(response("复盘结果","stop"));
        assertEquals("复盘结果",service.summarize("task",0,List.of(b,a),"知识来源","问题").markdown());
        var input=org.mockito.ArgumentCaptor.forClass(String.class);verify(client).complete(eq(TextPrompts.SUMMARY),input.capture());
        assertTrue(input.getValue().indexOf("first-event")<input.getValue().indexOf("second-event"));
        assertTrue(input.getValue().contains("知识来源"));
    }
}
