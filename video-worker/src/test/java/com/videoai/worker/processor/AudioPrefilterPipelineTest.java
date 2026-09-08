package com.videoai.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.*;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.rag.service.RagOrchestrator;
import com.videoai.worker.media.AudioPrefilterPreparationService;
import com.videoai.worker.screening.TextScreeningService;
import com.videoai.worker.segment.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Timeout(10)
class AudioPrefilterPipelineTest {
    final ObjectMapper json = new ObjectMapper();
    AudioPrefilterPipeline pipeline; AudioPrefilterPreparationService prep; TextScreeningService text;
    SegmentAnalysisService segments; AnalysisTaskMapper tasks; RagOrchestrator rag; AnalysisTask task; AnalysisSegmentMapper rows;
    SegmentAnalysisExecutor pool;
    @BeforeEach void setup() throws Exception {
        prep = mock(AudioPrefilterPreparationService.class); text = mock(TextScreeningService.class); segments = mock(SegmentAnalysisService.class);
        tasks = mock(AnalysisTaskMapper.class); rag = mock(RagOrchestrator.class); rows = mock(AnalysisSegmentMapper.class);
        var settings = mock(SegmentModelSettings.class); when(settings.snapshot()).thenReturn(Map.of("model", Map.of("supported", true)));
        var textCalls = mock(AnalysisTextCallMapper.class);
        when(tasks.isCurrentProcessing("task", 0)).thenReturn(1); when(tasks.updateStep(anyString(), anyInt(), anyString())).thenReturn(1);
        when(tasks.updateProgress(anyString(), anyInt(), anyInt())).thenReturn(1);
        task = new AnalysisTask(); task.setTaskId("task"); task.setPrompt("复盘");
        when(prep.prepareTranscript(eq("task"), eq(0), eq("source"), any())).thenReturn(new AudioPrefilterPreparationService.TranscriptManifest(1, 30000, "TRANSCRIBED", List.of()));
        screening(false);
        when(rag.buildPrompt(task)).thenReturn(PromptEnvelope.builder().retrievalContext("知识依据").build());
        when(prep.prepareSegments(eq("task"), eq(0), eq("source"), anyList(), any(), any())).thenAnswer(i -> {
            List<AudioPrefilterPreparationService.Range> ranges = i.getArgument(3);
            var guidance = ranges.isEmpty() ? null : i.<AudioPrefilterPreparationService.GuidanceLoader>getArgument(4).load();
            return new AudioPrefilterPreparationService.SegmentManifest(2, List.of(), guidance);
        });
        pipeline = new AudioPrefilterPipeline(prep, text, segments, settings, tasks, textCalls, rows, rag, json);
        var config = new SegmentAnalysisProperties(); config.setRequestIntervalMs(1); pool = new SegmentAnalysisExecutor(config);
    }
    @AfterEach void close() { pool.close(); }
    void screening(boolean empty) throws Exception {
        when(text.screen(eq("task"), eq(0), any())).thenReturn(new TextScreeningService.ScreeningManifest(1, empty ? "NO_CANDIDATES" : "CANDIDATES", "hash", "hash",
                List.of(), empty ? List.of() : List.of(new AudioPrefilterPreparationService.Range(0, 1000), new AudioPrefilterPreparationService.Range(10000, 11000)),
                BigDecimal.ZERO, List.of()));
    }
    SegmentAnalysisService.Completed review(int no) {
        return new SegmentAnalysisService.Completed(new SegmentReview(no, no * 10000L, no * 10000L + 1000, "观察", List.of(), List.of()), null, false);
    }
    @Test void ragPrecedesSegmentsAndCompletionNeverCallsSummary() throws Exception {
        var both = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(segments.analyze(eq("task"), eq(0), any(), any())).thenAnswer(i -> {
            List<SegmentAnalysisExecutor.Work<SegmentAnalysisService.Completed>> work = List.of(scope -> {
                both.countDown(); release.await(); return review(0);
            }, scope -> { both.countDown(); return review(1); });
            var results = new ArrayList<>(pool.execute(work, i.getArgument(2), () -> false, v -> {}));
            results.sort(Comparator.comparingLong(c -> c.review().startMs()));
            return new SegmentAnalysisService.Result(results);
        });
        var parent = Executors.newSingleThreadExecutor();
        try {
            var running = parent.submit(() -> pipeline.run(task, 0, "source", Instant.now().plusSeconds(5)));
            assertTrue(both.await(2, TimeUnit.SECONDS)); assertFalse(running.isDone()); verify(rag).buildPrompt(task);
            verify(text, never()).summarize(anyString(), anyInt(), anyList(), anyString(), anyString());
            release.countDown(); assertTrue(running.get().markdown().contains("2 个片段"));
            var order = inOrder(prep, text, segments, rag);
            order.verify(prep).prepareTranscript(eq("task"), eq(0), eq("source"), any()); order.verify(text).screen(eq("task"), eq(0), any());
            order.verify(prep).prepareSegments(eq("task"), eq(0), eq("source"), anyList(), any(), any());
            order.verify(rag).buildPrompt(task); order.verify(segments).analyze(eq("task"), eq(0), any(), any());
            verify(text, never()).summarize(anyString(), anyInt(), anyList(), anyString(), anyString());
        } finally { release.countDown(); parent.shutdownNow(); }
    }
    @Test void failureDoesNotSummarizeAndStillWaitsForStartedSibling() throws Exception {
        var both = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(segments.analyze(anyString(), anyInt(), any(), any())).thenAnswer(i -> {
            List<SegmentAnalysisExecutor.Work<Integer>> work = List.of(scope -> {
                both.countDown(); both.await(); throw new java.io.IOException("failed");
            }, scope -> { both.countDown(); release.await(); return 1; });
            pool.execute(work, i.getArgument(2), () -> false, v -> {}); throw new AssertionError("should fail");
        });
        var parent = Executors.newSingleThreadExecutor();
        try {
            var running = parent.submit(() -> pipeline.run(task, 0, "source", Instant.now().plusSeconds(5)));
            assertTrue(both.await(2, TimeUnit.SECONDS)); assertFalse(running.isDone()); release.countDown();
            assertThrows(ExecutionException.class, running::get); verify(rag).buildPrompt(task);
            verify(text, never()).summarize(anyString(), anyInt(), anyList(), anyString(), anyString());
        } finally { release.countDown(); parent.shutdownNow(); }
    }
    @Test void zeroCandidatesProducesCoverageNoticeWithoutVideoOrRag() throws Exception {
        screening(true);
        var result = pipeline.run(task, 0, "source", Instant.now().plusSeconds(5));
        assertTrue(result.markdown().contains("不能据此认定")); assertEquals(0L, result.tokensUsed()); verifyNoInteractions(segments, rag);
    }

    @Test void reusedManifestSkipsRagAndMismatchedUserPromptStopsBeforeVideo() throws Exception {
        when(prep.prepareSegments(eq("task"),eq(0),eq("source"),anyList(),any(),any()))
                .thenReturn(new AudioPrefilterPreparationService.SegmentManifest(2,List.of(),new SegmentGuidance("复盘","原参考")));
        when(segments.analyze(anyString(),anyInt(),any(),any())).thenReturn(new SegmentAnalysisService.Result(List.of(review(0))));
        assertTrue(pipeline.run(task,0,"source",Instant.now().plusSeconds(5)).markdown().contains("1 个片段"));
        verifyNoInteractions(rag);
        task.setPrompt("改变了问题"); clearInvocations(segments);
        assertThrows(java.io.IOException.class,()->pipeline.run(task,0,"source",Instant.now().plusSeconds(5)));
        verifyNoInteractions(segments);
    }
    @Test void expiredBudgetStopsBeforePreparationAndUnknownTokensStayNull() {
        assertThrows(Exception.class, () -> pipeline.run(task, 0, "source", Instant.now().minusSeconds(1)));
        verifyNoInteractions(prep);
        var usage = new AudioPrefilterPipeline.TokenUsage(); usage.add(null); assertNull(usage.total());
    }
}
