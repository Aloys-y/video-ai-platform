package com.videoai.api.service;

import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.utils.IdGenerator;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仅在 loadtest profile 下创建和统计合成任务。
 * 数据库任务写入入口；合成媒体需配合模拟业务执行器，不代表真实视频输入。
 */
@Service
@Profile("loadtest")
@RequiredArgsConstructor
public class LoadTestTaskService {

    private static final String UPLOAD_PREFIX = "loadtest:";

    private final AnalysisTaskMapper analysisTaskMapper;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public Map<String, Object> createTask(String runId, int sequence) {
        String taskId = IdGenerator.generateTaskId();
        String uploadId = UPLOAD_PREFIX + runId + ":" + sequence;

        AnalysisTask task = new AnalysisTask();
        task.setAnalysisMode(com.videoai.common.enums.AnalysisMode.AUDIO_PREFILTER.name());
        task.setTaskId(taskId);
        task.setTaskName("loadtest-" + runId);
        task.setUploadId(uploadId);
        task.setUserId(0L);
        task.setVideoUrl("loadtest/" + runId + "/" + sequence + ".mp4");
        task.setVideoDuration(600);
        // 不把 runId 放入提示词，确保相同 sequence 在不同参数实验中获得相同 Mock 延迟。
        task.setPrompt("loadtest sequence=" + sequence);
        task.setStatusEnum(TaskStatus.PENDING);
        task.setProgress(0);
        task.setAttemptNo(0);

        analysisTaskMapper.insert(task);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", runId);
        response.put("sequence", sequence);
        response.put("taskId", taskId);
        return response;
    }

    public Map<String, Object> report(String runId) {
        var rows=jdbcTemplate.queryForList("SELECT task_id,status,created_at,started_at,finished_at FROM analysis_task WHERE upload_id LIKE ? ORDER BY id",UPLOAD_PREFIX+runId+":%");
        return Map.of("runId",runId,"total",rows.size(),"tasks",rows);
    }
}
