package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AnalysisExecution;
import com.videoai.common.domain.AnalysisSegment;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 运行真实注解 SQL；适配 H2 的字符集/引擎声明和多列 ALTER 语法。 */
class AnalysisArtifactsMapperTest {
    private SqlSession session;
    private AnalysisExecutionMapper executions;
    private AnalysisSegmentMapper segments;
    private AnalysisTaskMapper tasks;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AnalysisExecutionMapper.class);
        config.addMapper(AnalysisSegmentMapper.class);
        config.addMapper(AnalysisTaskMapper.class);
        config.addMapper(AnalysisAsrPartMapper.class);
        config.addMapper(AnalysisTextCallMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        execute("""
                CREATE TABLE analysis_task (task_id VARCHAR(64) PRIMARY KEY, user_id BIGINT DEFAULT 1,
                  retry_count INT DEFAULT 0, status VARCHAR(20) DEFAULT 'PROCESSING', progress INT DEFAULT 0,
                  error_message TEXT, started_at TIMESTAMP, completed_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        Path root = Files.exists(Path.of("sql")) ? Path.of(".") : Path.of("..");
        String migration = (Files.readString(root.resolve("sql/V1.5__audio_prefilter_contract.sql"))
                + "\n" + Files.readString(root.resolve("sql/V1.6__asr_parts.sql"))
                + "\n" + Files.readString(root.resolve("sql/V1.7__text_call_records.sql")))
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("COMMENT\\s+'[^']*'", "")
                .replaceAll("COLLATE[= ]+\\w+", "")
                .replaceAll(",\\s*ADD COLUMN", "; ALTER TABLE analysis_task ADD COLUMN")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) execute(sql);
        execute("INSERT INTO analysis_task(task_id, analysis_mode) VALUES ('task-test', 'AUDIO_PREFILTER')");
        executions = session.getMapper(AnalysisExecutionMapper.class);
        segments = session.getMapper(AnalysisSegmentMapper.class);
        tasks = session.getMapper(AnalysisTaskMapper.class);
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    private void execute(String sql) throws SQLException {
        try (Statement statement = session.getConnection().createStatement()) { statement.execute(sql); }
    }

    private AnalysisExecution execution(int no, String hash) {
        var e = new AnalysisExecution();
        e.setTaskId("task-test"); e.setExecutionNo(no); e.setAnalysisMode("AUDIO_PREFILTER");
        e.setConfigSnapshot("{\"model\":\"test\"}"); e.setConfigHash(hash.repeat(64)); e.setInputHash("a".repeat(64));
        return e;
    }

    private AnalysisSegment segment(int no) {
        var s = new AnalysisSegment();
        s.setTaskId("task-test"); s.setExecutionNo(no); s.setSegmentNo(0);
        s.setStartMs(1000L); s.setEndMs(3000L); s.setObjectKey("tasks/task-test/" + no + "/0.mp4");
        return s;
    }

    @Test
    void fullArtifactsAndSuccessfulSegmentsReuseOnlyExactConfigurationAndInput() throws Exception {
        executions.insert(execution(0, "b"));
        executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.TRANSCRIPT, "transcript");
        executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.CANDIDATES, "candidates");
        executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.SEGMENTS, "segments");
        var old = segment(0); segments.insertPrepared(old); segments.markProcessing("task-test", 0, 0);
        old.setStatus("SUCCEEDED"); old.setResult("saved"); old.setUsageJson("{\"tokens\":10}"); segments.finish(old);
        execute("UPDATE analysis_task SET retry_count=1"); executions.insert(execution(1, "b"));
        assertEquals(0, executions.selectReusable("task-test", 1).getExecutionNo());
        var target = segment(1); target.setObjectKey(old.getObjectKey());
        assertEquals(0, segments.selectReusable(target).getExecutionNo());
        assertEquals(1, segments.reuseSucceeded("task-test", 1, 0, 0, 0, 1000, 3000));
        var reused = segments.selectExecution("task-test", 1).get(0);
        assertEquals(0, reused.getReusedExecutionNo()); assertNull(reused.getUsageJson());
        target.setEndMs(4000L); assertNull(segments.selectReusable(target));
        execute("UPDATE analysis_task SET retry_count=2"); executions.insert(execution(2, "c"));
        assertNull(executions.selectReusable("task-test", 2));
        target.setExecutionNo(2); target.setEndMs(3000L); assertNull(segments.selectReusable(target));
    }

    @Test
    void segmentResponseIsWriteOnceAndCancelledOrOldExecutionsCannotFinish() throws Exception {
        executions.insert(execution(0, "b")); var s = segment(0);
        assertEquals(1, tasks.isCurrentProcessing("task-test", 0));
        segments.insertPrepared(s); assertEquals(1, segments.markProcessing("task-test", 0, 0));
        s.setUsageJson("{\"responseObjectKey\":\"raw\",\"reportedUsage\":{\"tokens\":10}}");
        assertEquals(1, segments.recordResponse(s)); assertEquals(0, segments.recordResponse(s));
        execute("UPDATE analysis_task SET status='CANCELLED'");
        assertEquals(0, tasks.isCurrentProcessing("task-test", 0));
        s.setStatus("SUCCEEDED"); s.setResult("late"); assertEquals(0, segments.finish(s));
        execute("UPDATE analysis_task SET status='PROCESSING', retry_count=1");
        assertEquals(0, segments.finish(s)); assertEquals(0, segments.recordResponse(s));
        assertEquals("PROCESSING", segments.selectExecution("task-test", 0).get(0).getStatus());
    }

    @Test
    void textCallsCannotBeDuplicatedOrOverwriteSavedResponses() throws Exception {
        executions.insert(execution(0,"b"));var mapper=session.getMapper(AnalysisTextCallMapper.class);
        var call=new com.videoai.common.domain.AnalysisTextCall();call.setTaskId("task-test");call.setExecutionNo(0);
        call.setPurpose("SCREEN");call.setBatchNo(0);call.setRequestHash("a".repeat(64));
        assertEquals(1,mapper.claim(call));assertThrows(RuntimeException.class,()->mapper.claim(call));
        execute("UPDATE analysis_task SET status='CANCELLED'");
        call.setResponseObjectKey("raw.json");assertEquals(1,mapper.recordResponse(call));
        call.setResponseObjectKey("changed.json");assertEquals(0,mapper.recordResponse(call));
        assertEquals("raw.json",mapper.select(call).getResponseObjectKey());
        call.setBatchNo(1);assertEquals(0,mapper.claim(call));
    }

    @Test
    void asrSubmissionIsClaimedOnceAndLateIdIsPreserved() throws Exception {
        executions.insert(execution(0, "b"));
        var mapper = session.getMapper(AnalysisAsrPartMapper.class);
        var part = new com.videoai.common.domain.AnalysisAsrPart();
        part.setTaskId("task-test"); part.setExecutionNo(0); part.setPartNo(0);
        part.setStartMs(0L); part.setEndMs(1000L); part.setAudioObjectKey("audio/0.wav");
        assertEquals(1, mapper.insert(part));
        assertEquals(1, mapper.claimSubmission(part)); assertEquals(0, mapper.claimSubmission(part));
        execute("UPDATE analysis_task SET status = 'CANCELLED'");
        part.setAsrTaskId("remote-id");
        assertEquals(1, mapper.recordSubmitted(part));
        assertEquals("remote-id", mapper.selectExecution("task-test", 0).get(0).getAsrTaskId());
        part.setTranscriptObjectKey("transcript.json"); assertEquals(0, mapper.recordResult(part));
        execute("UPDATE analysis_task SET status = 'PROCESSING', retry_count = 1");
        executions.insert(execution(1, "b"));
        assertEquals(1, mapper.selectReusablePlan("task-test", 1, "a".repeat(64), execution(1, "b").getConfigSnapshot()).size());
        assertEquals(0, mapper.selectReusablePlan("task-test", 1, "c".repeat(64), execution(1, "b").getConfigSnapshot()).size());
    }

    @Test
    void checkpointsAreWriteOnceAndRetryPreservesOldExecution() throws Exception {
        assertEquals(1, executions.insert(execution(0, "b")));
        assertEquals(1, executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.TRANSCRIPT, "v0/transcript.json"));
        assertEquals(0, executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.TRANSCRIPT, "changed.json"));
        assertThrows(RuntimeException.class, () -> executions.insert(execution(0, "c")));
        assertEquals(1, tasks.updateStep("task-test", 0, "TRANSCRIBING"));
        execute("UPDATE analysis_task SET status = 'FAILED'");
        assertEquals(1, tasks.resetForManualRetry("task-test", 1L));
        try (var st = session.getConnection().createStatement(); var rs = st.executeQuery("SELECT retry_count, current_step FROM analysis_task")) {
            assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); assertNull(rs.getString(2));
        }
        assertEquals(0, tasks.updateStep("task-test", 0, "SCREENING"));
        assertEquals(0, executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.CANDIDATES, "late.json"));
        execute("UPDATE analysis_task SET status = 'PROCESSING'");
        assertEquals(1, executions.insert(execution(1, "b")));
        assertEquals("v0/transcript.json", executions.selectExecution("task-test", 0).getTranscriptObjectKey());
        assertNull(executions.selectExecution("task-test", 1).getTranscriptObjectKey());
    }

    @Test
    void duplicateAndLateSegmentResultsCannotOverwriteSuccess() throws Exception {
        executions.insert(execution(0, "b"));
        AnalysisSegment s = segment(0);
        assertEquals(1, segments.insertPrepared(s));
        assertThrows(RuntimeException.class, () -> segments.insertPrepared(s));
        assertEquals(1, segments.selectExecution("task-test", 0).size());
        assertEquals(1, segments.markProcessing("task-test", 0, 0));
        s.setStatus("SUCCEEDED"); s.setResult("original"); s.setUsageJson("{\"tokens\":100}");
        assertEquals(1, segments.finish(s));
        s.setResult("late overwrite");
        assertEquals(0, segments.finish(s));
        assertEquals("original", segments.selectExecution("task-test", 0).get(0).getResult());
        execute("UPDATE analysis_task SET retry_count = 1");
        assertEquals(0, segments.insertPrepared(segment(0)));
    }

    @Test
    void reuseRequiresMatchingInputConfigAndRangeAndRecordsSource() throws Exception {
        executions.insert(execution(0, "b"));
        var s = segment(0); segments.insertPrepared(s); segments.markProcessing("task-test", 0, 0);
        s.setStatus("SUCCEEDED"); s.setResult("kept"); s.setUsageJson("{\"tokens\":100}"); segments.finish(s);
        execute("UPDATE analysis_task SET retry_count = 1");
        executions.insert(execution(1, "b"));
        assertEquals(0, segments.reuseSucceeded("task-test", 1, 0, 0, 0, 1000, 4000));
        assertEquals(1, segments.reuseSucceeded("task-test", 1, 0, 0, 0, 1000, 3000));
        var reused = segments.selectExecution("task-test", 1).get(0);
        assertEquals("kept", reused.getResult()); assertEquals(0, reused.getReusedExecutionNo());
        assertEquals(0, reused.getReusedSegmentNo()); assertNull(reused.getUsageJson());
        execute("UPDATE analysis_task SET retry_count = 2");
        executions.insert(execution(2, "c"));
        assertEquals(0, segments.reuseSucceeded("task-test", 2, 0, 0, 0, 1000, 3000));
        execute("UPDATE analysis_task SET retry_count = 3");
        var changedInput = execution(3, "b"); changedInput.setInputHash("d".repeat(64));
        executions.insert(changedInput);
        assertEquals(0, segments.reuseSucceeded("task-test", 3, 0, 0, 0, 1000, 3000));
    }

    @Test
    void cancelledTaskAndInvalidRangesAreRejected() throws Exception {
        executions.insert(execution(0, "b"));
        var s = segment(0); s.setEndMs(999L);
        assertThrows(RuntimeException.class, () -> segments.insertPrepared(s));
        assertThrows(RuntimeException.class, () -> executions.bindOnce("task-test", 0,
                AnalysisExecutionMapper.Artifact.TRANSCRIPT, "https://example.com/signed"));
        execute("UPDATE analysis_task SET status = 'CANCELLED'");
        assertEquals(0, segments.insertPrepared(segment(0)));
        assertEquals(0, executions.bindOnce("task-test", 0, AnalysisExecutionMapper.Artifact.ASR_TASK_ID, "asr-123"));
    }
}
