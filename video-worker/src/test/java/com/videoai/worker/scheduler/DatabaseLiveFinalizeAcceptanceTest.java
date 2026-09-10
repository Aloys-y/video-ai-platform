package com.videoai.worker.scheduler;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只导出已完成的验收任务并做无付费故障注入，不重新调用视频链路。 */
@EnabledIfSystemProperty(named="dispatch.finalize.acceptance",matches="true")
@Timeout(180)
class DatabaseLiveFinalizeAcceptanceTest {
    @Test void exportSavedResultsAndKillProcess() throws Exception {
        var output=Path.of(Files.readString(DatabaseLiveVideoAcceptanceTest.ROOT.resolve("logs/database-live-latest.txt")).strip());
        String database=output.getFileName().toString();
        assertTrue(database.matches("videoai_live_it_[a-f0-9]{32}"));
        assertTrue(output.normalize().startsWith(DatabaseLiveVideoAcceptanceTest.ROOT.resolve("logs")));
        DatabaseLiveVideoAcceptanceTest.output=output;DatabaseLiveVideoAcceptanceTest.database=database;
        var env=DatabaseLiveVideoAcceptanceTest.credentials();
        var jdbc=new JdbcTemplate(DatabaseLiveVideoAcceptanceTest.source(env,database));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='live-video-3'",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM analysis_segment WHERE task_id='live-video-3' AND status='SUCCEEDED'",Integer.class));
        for(String table:List.of("analysis_task","analysis_execution","analysis_asr_part","analysis_text_call","analysis_segment"))
            DatabaseLiveVideoAcceptanceTest.write(table+".jsonl",jdbc.queryForList("SELECT * FROM "+table+" WHERE task_id='live-video-3'"));
        DatabaseLiveVideoAcceptanceTest.verifyProcessCrash(env,jdbc);
        System.out.println("LIVE_SAVED_RESULTS_AND_PROCESS_CRASH_OK");
    }
}
