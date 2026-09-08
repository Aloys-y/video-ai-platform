package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.media.*;
import com.videoai.worker.screening.SegmentReviewParser;
import com.videoai.worker.service.provider.AiVideoProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class SegmentAnalysisServiceTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final Map<Integer, AnalysisSegment> rows = new ConcurrentHashMap<>();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicBoolean active = new AtomicBoolean(true);
    SegmentAnalysisService service; SegmentAnalysisExecutor executor; AiVideoProvider provider;
    AnalysisSegmentMapper mapper; AnalysisExecution execution; StorageService storage;

    @BeforeEach void setup() throws Exception {
        var properties = new SegmentAnalysisProperties(); properties.setRequestIntervalMs(1);
        executor = new SegmentAnalysisExecutor(properties);
        var mediaConfig = new MediaProperties(); mediaConfig.setTempRoot(root.toString()); mediaConfig.setMinFreeBytes(0);
        provider = mock(AiVideoProvider.class); mapper = mock(AnalysisSegmentMapper.class); storage = mock(StorageService.class);
        when(provider.segmentSettings()).thenReturn(Map.of("supported", true, "model", "test"));
        when(provider.getName()).thenReturn("test"); when(provider.getPresignedUrlExpireHours()).thenReturn(2);
        var settings = new SegmentModelSettings(provider);
        execution = new AnalysisExecution(); execution.setTaskId("task"); execution.setExecutionNo(0); execution.setSegmentsObjectKey("plan");
        execution.setConfigSnapshot(json.writeValueAsString(Map.of("settings", Map.of("segments", settings.snapshot()))));
        var executions = mock(AnalysisExecutionMapper.class); when(executions.selectExecution("task", 0)).thenReturn(execution);
        var tasks = mock(AnalysisTaskMapper.class); when(tasks.isCurrentProcessing("task", 0)).thenAnswer(i -> active.get() ? 1 : 0);
        when(mapper.selectExecution("task", 0)).thenAnswer(i -> rows.values().stream().sorted(Comparator.comparingInt(AnalysisSegment::getSegmentNo)).map(this::copy).toList());
        when(mapper.insertPrepared(any())).thenAnswer(i -> { var row = copy(i.getArgument(0)); return rows.putIfAbsent(row.getSegmentNo(), row) == null ? 1 : 0; });
        when(mapper.markProcessing(anyString(), anyInt(), anyInt())).thenAnswer(i -> {
            synchronized (rows) {
                var row = rows.get(i.<Integer>getArgument(2));
                if (!active.get() || !"PREPARED".equals(row.getStatus())) return 0;
                row.setStatus("PROCESSING"); return 1;
            }
        });
        when(mapper.recordResponse(any())).thenAnswer(i -> {
            synchronized (rows) {
                var row = i.<AnalysisSegment>getArgument(0); var saved = rows.get(row.getSegmentNo());
                if (!active.get() || saved.getUsageJson() != null || !"PROCESSING".equals(saved.getStatus())) return 0;
                saved.setUsageJson(row.getUsageJson()); return 1;
            }
        });
        when(mapper.finish(any())).thenAnswer(i -> {
            synchronized (rows) {
                var row = i.<AnalysisSegment>getArgument(0); var saved = rows.get(row.getSegmentNo());
                if (!active.get() || !"PROCESSING".equals(saved.getStatus())) return 0;
                rows.put(row.getSegmentNo(), copy(row)); return 1;
            }
        });
        doAnswer(i -> { Files.write((Path)i.getArgument(1), objects.get(i.<String>getArgument(0))); return null; })
                .when(storage).downloadToFile(anyString(), any(), anyLong(), any(), anyLong());
        when(storage.putArtifact(any(), anyString())).thenAnswer(i -> {
            String key = "raw/" + UUID.randomUUID(); objects.put(key, Files.readAllBytes(i.getArgument(0))); return key;
        });
        when(storage.getPresignedUrl(anyString(), anyInt())).thenAnswer(i -> "https://signed.example/" + i.getArgument(0));
        when(provider.callDetailed(anyString(), anyString())).thenReturn(response("ok"));
        service = new SegmentAnalysisService(executor, properties, settings, new SegmentPersistenceService(mapper), provider,
                new SegmentReviewParser(json), executions, tasks, storage, new MediaPreparationService(mediaConfig, json), mediaConfig, json);
        plan(3);
    }
    @AfterEach void close() { executor.close(); }
    AnalysisSegment copy(AnalysisSegment row) { return json.convertValue(row, AnalysisSegment.class); }
    AiVideoProvider.DetailedResult response(String summary) throws Exception {
        return new AiVideoProvider.DetailedResult(json.writeValueAsString(Map.of("summary", summary, "events",
                List.of(Map.of("startMs", 100, "endMs", 500, "observation", "画面中出现对手")), "uncertainties", List.of())),
                "{\"input_tokens\":100,\"output_tokens\":20}", "request-id", "stop");
    }
    void plan(int size) throws Exception {
        List<PreparedSegment> plan = new ArrayList<>();
        for (int i = 0; i < size; i++) plan.add(new PreparedSegment(i, i * 10000L, i * 10000L + 1000, "clip-" + i));
        objects.put("plan", json.writeValueAsBytes(new AudioPrefilterPreparationService.SegmentManifest(2, plan, new SegmentGuidance("复盘", "掩体使用参考"))));
    }
    SegmentAnalysisService.Result run() throws Exception { return service.analyze("task", 0, Instant.now().plusSeconds(5), p -> {}); }

    @Test void outOfOrderCompletionReturnsOriginalTimelineAfterAllWritesAndCanResume() throws Exception {
        var releaseFirst = new CountDownLatch(1); var secondSaved = new CountDownLatch(1);
        when(provider.callDetailed(contains("clip-0"), anyString())).thenAnswer(i -> { releaseFirst.await(); return response("first"); });
        List<Integer> progress = new CopyOnWriteArrayList<>();
        var parent = Executors.newSingleThreadExecutor();
        try {
            var future = parent.submit(() -> service.analyze("task", 0, Instant.now().plusSeconds(5), p -> {
                progress.add(p.succeeded());
                if (p.completed().stream().anyMatch(c -> c.review().segmentNo() == 1)) secondSaved.countDown();
            }));
            assertTrue(secondSaved.await(2, TimeUnit.SECONDS)); assertFalse(future.isDone());
            assertEquals("SUCCEEDED", rows.get(1).getStatus()); releaseFirst.countDown();
            var result = future.get();
            assertEquals(List.of(0, 1, 2), result.reviews().stream().map(SegmentReview::segmentNo).toList());
            assertEquals(10100, result.reviews().get(1).events().get(0).startMs());
            assertEquals(List.of(0, 1, 2, 3), progress);
            assertTrue(rows.values().stream().allMatch(r -> "SUCCEEDED".equals(r.getStatus())));
            for (var row : rows.values()) assertTrue(objects.containsKey(json.readTree(row.getUsageJson()).path("responseObjectKey").asText()));
            assertTrue(run().segments().stream().allMatch(SegmentAnalysisService.Completed::reused));
            verify(provider, times(3)).callDetailed(anyString(), argThat(p -> p.startsWith(SegmentReviewParser.VIDEO_PROMPT) && p.contains("掩体使用参考") && p.contains("复盘")));
        } finally { releaseFirst.countDown(); parent.shutdownNow(); }
    }

    @Test void invalidResultPreservesRawAndUsageWithoutRepeatingPaidCall() throws Exception {
        plan(1);
        when(provider.callDetailed(anyString(), anyString())).thenReturn(new AiVideoProvider.DetailedResult("bad-json", "{\"tokens\":10}", "id", "stop"));
        assertThrows(SegmentAnalysisExecutor.BatchFailure.class, this::run);
        assertEquals("FAILED", rows.get(0).getStatus());
        var receipt = json.readTree(rows.get(0).getUsageJson());
        assertTrue(objects.containsKey(receipt.path("responseObjectKey").asText())); assertEquals(10, receipt.path("reportedUsage").path("tokens").asInt());
        assertThrows(Exception.class, this::run); verify(provider, times(1)).callDetailed(anyString(), anyString());
    }

    @Test void failedSegmentDoesNotPreventOtherResultsFromBeingPersisted() throws Exception {
        when(provider.callDetailed(contains("clip-0"), anyString()))
                .thenReturn(new AiVideoProvider.DetailedResult("bad-json", "{}", "id", "stop"));
        var failure = assertThrows(SegmentAnalysisExecutor.BatchFailure.class, this::run);
        assertTrue(failure.converged()); assertEquals(2, failure.completed().size());
        assertEquals("FAILED", rows.get(0).getStatus());
        assertEquals("SUCCEEDED", rows.get(1).getStatus());
        assertEquals("SUCCEEDED", rows.get(2).getStatus());
        verify(provider, times(3)).callDetailed(anyString(), anyString());
    }

    @Test void recordedResponseResumesParsingButUnknownProcessingDoesNotResubmit() throws Exception {
        plan(1); var segment = new PreparedSegment(0, 0, 1000, "clip-0");
        var row = SegmentPersistenceService.row("task", 0, segment); row.setStatus("PROCESSING"); rows.put(0, row);
        assertThrows(Exception.class, this::run); verify(provider, never()).callDetailed(anyString(), anyString());
        objects.put("saved", json.writeValueAsBytes(response("saved"))); row.setUsageJson("{\"responseObjectKey\":\"saved\"}");
        assertEquals("saved", run().reviews().get(0).summary()); verify(provider, never()).callDetailed(anyString(), anyString());
    }

    @Test void changedConfigEmptyPlanAndMalformedPlanNeverCallModel() throws Exception {
        execution.setConfigSnapshot("{\"settings\":{}}"); assertThrows(Exception.class, this::run);
        verify(provider, never()).callDetailed(anyString(), anyString());
        execution.setConfigSnapshot(json.writeValueAsString(Map.of("settings", Map.of("segments", new SegmentModelSettings(provider).snapshot()))));
        plan(0); assertTrue(run().reviews().isEmpty());
        objects.put("plan", json.writeValueAsBytes(new AudioPrefilterPreparationService.SegmentManifest(1,
                List.of(new PreparedSegment(1, 0, 1000, "bad")))));
        assertThrows(Exception.class, this::run); verify(provider, never()).callDetailed(anyString(), anyString());
    }

    @Test void databaseFailureOrCancellationCannotPublishSuccess() throws Exception {
        plan(1); doThrow(new IllegalStateException("DB unavailable")).when(mapper).finish(any());
        assertThrows(SegmentAnalysisExecutor.BatchFailure.class, this::run);
        assertEquals("PROCESSING", rows.get(0).getStatus()); assertNotNull(rows.get(0).getUsageJson());
        active.set(false); assertThrows(Exception.class, this::run);
        verify(provider, times(1)).callDetailed(anyString(), anyString());
    }

    @Test void savedResponseSurvivesDatabaseFailureAndRecoveryDoesNotCallModelAgain() throws Exception {
        plan(1);
        var failOnce = new AtomicBoolean(true);
        doAnswer(i -> {
            if (failOnce.getAndSet(false)) throw new org.springframework.dao.DataAccessResourceFailureException("offline");
            AnalysisSegment row = i.getArgument(0); rows.put(row.getSegmentNo(), copy(row)); return 1;
        }).when(mapper).finish(any());
        var failure = assertThrows(SegmentAnalysisExecutor.BatchFailure.class, this::run);
        assertTrue(failure.persistenceUnsettled());
        assertEquals("PROCESSING", rows.get(0).getStatus()); assertNotNull(rows.get(0).getUsageJson());
        assertEquals(1, run().segments().size()); assertEquals("SUCCEEDED", rows.get(0).getStatus());
        verify(provider, times(1)).callDetailed(anyString(), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void transactionCommitFailureMustRemainRecoverableWithoutCallingModelAgain(boolean committed) throws Exception {
        plan(1);
        var failOnce = new AtomicBoolean(true);
        doAnswer(i -> {
            if (failOnce.getAndSet(false)) {
                if (committed) { AnalysisSegment saved = i.getArgument(0); rows.put(saved.getSegmentNo(), copy(saved)); }
                throw new org.springframework.transaction.TransactionSystemException("commit outcome unknown");
            }
            AnalysisSegment row = i.getArgument(0); rows.put(row.getSegmentNo(), copy(row)); return 1;
        }).when(mapper).finish(any());
        var failure = assertThrows(SegmentAnalysisExecutor.BatchFailure.class, this::run);
        assertTrue(failure.persistenceUnsettled(), "事务提交异常不能被当作模型分析失败");
        assertEquals(committed ? "SUCCEEDED" : "PROCESSING", rows.get(0).getStatus());
        assertNotNull(rows.get(0).getUsageJson());
        assertEquals(1, run().segments().size());
        verify(provider, times(1)).callDetailed(anyString(), anyString());
    }
}
