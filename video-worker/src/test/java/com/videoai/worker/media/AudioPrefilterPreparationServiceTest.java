package com.videoai.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.TranscriptUtterance;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.asr.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioPrefilterPreparationServiceTest {
    @TempDir Path root;
    private AudioPrefilterPreparationService service;
    private CloudAsrClient asr;
    private AnalysisTaskMapper tasks;
    private AnalysisExecutionMapper executions;
    private final AtomicReference<AnalysisExecution> execution = new AtomicReference<>();
    private List<AnalysisAsrPart> plan;
    private StorageService storage;
    private MediaPreparationService media;
    private final Map<String, byte[]> objects = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        plan = new ArrayList<>();
        var props = new MediaProperties(); props.setTempRoot(root.resolve("work").toString()); props.setMinFreeBytes(0);
        var json = new ObjectMapper();
        media = spy(new MediaPreparationService(props, json));
        doReturn(new MediaPreparationService.VideoInfo(2000, 0, true)).when(media).probe(any(), any());
        Path audio = root.resolve("fixture.wav"); Files.writeString(audio, "audio");
        doReturn(List.of(new MediaPreparationService.AudioPart(0, 0, 2000, audio))).when(media).extractAudio(any(), any(), any());
        storage = mock(StorageService.class); asr = mock(CloudAsrClient.class);
        tasks = mock(AnalysisTaskMapper.class); executions = mock(AnalysisExecutionMapper.class);
        var parts = mock(AnalysisAsrPartMapper.class); var persistence = mock(AsrPartPersistenceService.class);
        when(tasks.updateStep(anyString(), anyInt(), anyString())).thenReturn(1);
        when(executions.selectExecution(anyString(), anyInt())).thenAnswer(i -> execution.get());
        when(executions.insert(any())).thenAnswer(i -> {execution.set(i.getArgument(0)); return 1;});
        when(executions.bindOnce(anyString(), anyInt(), any(), anyString())).thenAnswer(i -> {
            if (i.getArgument(2) == AnalysisExecutionMapper.Artifact.TRANSCRIPT) execution.get().setTranscriptObjectKey(i.getArgument(3));
            if (i.getArgument(2) == AnalysisExecutionMapper.Artifact.SEGMENTS) execution.get().setSegmentsObjectKey(i.getArgument(3));
            return 1;
        });
        when(parts.selectExecution(anyString(), anyInt())).thenAnswer(i -> plan);
        when(parts.selectReusablePlan(anyString(), anyInt(), anyString(), anyString())).thenReturn(List.of());
        doAnswer(i -> {plan = i.getArgument(0); return null;}).when(persistence).savePlan(anyList());
        when(parts.claimSubmission(any())).thenAnswer(i -> {((AnalysisAsrPart)i.getArgument(0)).setAsrTaskId("SUBMITTING"); return 1;});
        when(parts.recordSubmitted(any())).thenReturn(1); when(parts.recordResult(any())).thenReturn(1);
        objects.put("original.mp4", "video".getBytes());
        doAnswer(i -> {Files.write((Path)i.getArgument(1), objects.get((String)i.getArgument(0))); return null;})
                .when(storage).downloadToFile(anyString(), any(), anyLong(), any(), anyLong());
        when(storage.putArtifact(any(), anyString())).thenAnswer(i -> {
            String key = "artifact/" + UUID.randomUUID(); objects.put(key, Files.readAllBytes(i.getArgument(0))); return key;
        });
        when(storage.getPresignedUrl(anyString(), anyInt())).thenReturn("https://audio.example/test");
        var segmentSettings = mock(com.videoai.worker.segment.SegmentModelSettings.class);
        when(segmentSettings.snapshot()).thenReturn(Map.of("version", "p4-test"));
        service = new AudioPrefilterPreparationService(media, props, new AsrProperties(), new com.videoai.worker.screening.TextAnalysisProperties(), segmentSettings, asr, storage,
                executions, parts, tasks, persistence, json);
    }

    @Test void persistsTranscriptAndResumesWithoutAnotherPaidCall() throws Exception {
        when(asr.submit(anyString())).thenReturn("remote-id");
        when(asr.query("remote-id")).thenReturn(new CloudAsrClient.Query("SUCCEEDED", "https://result.example/test", null));
        when(asr.downloadResult(anyString(), anyInt(), anyLong(), anyLong())).thenReturn(new CloudAsrClient.Transcript(
                List.of(new TranscriptUtterance("p0-u0", 100, 1000, "前面有人", 0)), new ObjectMapper().createObjectNode()));
        var first = service.prepareTranscript("task", 0, "original.mp4");
        assertEquals("TRANSCRIBED", first.outcome()); assertNotNull(execution.get().getTranscriptObjectKey());
        assertThrows(IOException.class, () -> service.prepareSegments("task",0,"original.mp4",List.of()));
        execution.get().setCandidatesObjectKey("candidates");
        objects.put("candidates",new ObjectMapper().writeValueAsBytes(new com.videoai.worker.screening.TextScreeningService.ScreeningManifest(
                1,"CANDIDATES",execution.get().getConfigHash(),"transcript-hash",List.of(),
                List.of(new AudioPrefilterPreparationService.Range(0,1000)),java.math.BigDecimal.ZERO,List.of())));
        var mismatch=assertThrows(IOException.class,()->service.prepareSegments("task",0,"original.mp4",
                List.of(new AudioPrefilterPreparationService.Range(0,1500))));
        assertTrue(mismatch.getMessage().contains("冻结"));
        var second = service.prepareTranscript("task", 0, "original.mp4");
        assertEquals(first, second); verify(asr, times(1)).submit(anyString());
        assertEquals("remote-id", plan.get(0).getAsrTaskId());
        try (var paths = Files.list(root.resolve("work"))) { assertEquals(0, paths.count()); }
    }

    @Test void uncertainSubmissionIsNotAutomaticallyRepeated() throws Exception {
        when(asr.submit(anyString())).thenThrow(new IOException("request timed out"));
        assertThrows(IOException.class, () -> service.prepareTranscript("task", 0, "original.mp4"));
        assertEquals("SUBMITTING", plan.get(0).getAsrTaskId());
        var error = assertThrows(IOException.class, () -> service.prepareTranscript("task", 0, "original.mp4"));
        assertTrue(error.getMessage().contains("提交结果未知"));
        verify(asr, times(1)).submit(anyString()); verify(asr, never()).query(anyString());
    }

    @Test void guidanceIsFrozenOnceAndReusedWithoutRetrieval() throws Exception {
        when(asr.submit(anyString())).thenReturn("remote-id");
        when(asr.query("remote-id")).thenReturn(new CloudAsrClient.Query("SUCCEEDED", "https://result.example/test", null));
        when(asr.downloadResult(anyString(), anyInt(), anyLong(), anyLong())).thenReturn(new CloudAsrClient.Transcript(
                List.of(new TranscriptUtterance("u",0,1000,"接敌",0)),new ObjectMapper().createObjectNode()));
        Path source;
        try (var workspace = service.openWorkspace()) {
        source = workspace.file("source.mp4");
        service.prepareTranscript("task",0,"original.mp4",workspace);
        var ranges=List.of(new AudioPrefilterPreparationService.Range(0,1000));
        execution.get().setCandidatesObjectKey("candidates");
        objects.put("candidates",new ObjectMapper().writeValueAsBytes(new com.videoai.worker.screening.TextScreeningService.ScreeningManifest(
                1,"CANDIDATES",execution.get().getConfigHash(),"transcript",List.of(),ranges,java.math.BigDecimal.ZERO,List.of())));
        doAnswer(i -> {
            var ws=i.<MediaPreparationService.Workspace>getArgument(0);
            return Files.writeString(ws.file("clip.mp4"),"clip");
        }).when(media).clip(any(),any(),any(),anyInt(),anyLong(),anyLong());
        var guidance=new com.videoai.common.analysis.SegmentGuidance("复盘","参考 A");
        var first=service.prepareSegments("task",0,"original.mp4",ranges,()->guidance,workspace);
        assertEquals(2,first.schemaVersion()); assertEquals(guidance,first.guidance());
        var again=service.prepareSegments("task",0,"original.mp4",ranges,()->{throw new AssertionError("不得重新检索");},workspace);
        assertEquals(first,again);
        verify(storage,times(1)).downloadToFile(eq("original.mp4"),any(),anyLong(),any(),anyLong());
        assertTrue(Files.exists(source));
        }
        assertFalse(Files.exists(source));
    }

    @Test void newGenerationBindsVerifiedHistoricalArtifactsWithoutAsrSubmission() throws Exception {
        var old = new AnalysisExecution(); old.setTranscriptObjectKey("old-transcript"); old.setCandidatesObjectKey("old-candidates"); old.setSegmentsObjectKey("old-segments");
        objects.put("old-transcript", new ObjectMapper().writeValueAsBytes(new AudioPrefilterPreparationService.TranscriptManifest(
                1, 2000, "TRANSCRIBED", List.of(new TranscriptUtterance("u0", 0, 1000, "交战", 0)))));
        when(executions.selectReusable("task", 1)).thenReturn(old);
        assertEquals("TRANSCRIBED", service.prepareTranscript("task", 1, "original.mp4").outcome());
        verify(executions).insert(any());
        verify(executions).bindOnce("task", 1, AnalysisExecutionMapper.Artifact.TRANSCRIPT, "old-transcript");
        verify(executions).bindOnce("task", 1, AnalysisExecutionMapper.Artifact.CANDIDATES, "old-candidates");
        verify(executions).bindOnce("task", 1, AnalysisExecutionMapper.Artifact.SEGMENTS, "old-segments");
        verifyNoInteractions(asr);
    }

    @Test void staleExecutionAndInvalidClipBudgetsStopBeforeExternalWork() throws Exception {
        when(tasks.updateStep(anyString(), anyInt(), anyString())).thenReturn(0);
        assertThrows(IOException.class, () -> service.prepareTranscript("task", 0, "original.mp4"));
        verifyNoInteractions(asr, storage);
        assertThrows(IOException.class, () -> service.validateRanges(List.of(new AudioPrefilterPreparationService.Range(900, 800)), 2000));
        assertThrows(IOException.class, () -> service.validateRanges(List.of(new AudioPrefilterPreparationService.Range(0, 2500)), 2000));
        assertThrows(IOException.class, () -> service.validateRanges(List.of(new AudioPrefilterPreparationService.Range(0, 1000),
                new AudioPrefilterPreparationService.Range(500, 1500)), 2000));
    }
}
