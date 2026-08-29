package com.videoai.api.service;

import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.utils.IdGenerator;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.service.TaskOutboxService;
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
 * MySQL 任务、Outbox 事务和后续 Kafka/Worker 链路全部复用生产实现。
 */
@Service
@Profile("loadtest")
@RequiredArgsConstructor
public class LoadTestTaskService {

    private static final String UPLOAD_PREFIX = "loadtest:";

    private final AnalysisTaskMapper analysisTaskMapper;
    private final TaskOutboxService taskOutboxService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public Map<String, Object> createTask(String runId, int sequence) {
        String taskId = IdGenerator.generateTaskId();
        String uploadId = UPLOAD_PREFIX + runId + ":" + sequence;

        AnalysisTask task = new AnalysisTask();
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
        task.setRetryCount(0);

        analysisTaskMapper.insert(task);
        taskOutboxService.createExecuteOutbox(task, 0, LocalDateTime.now());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", runId);
        response.put("sequence", sequence);
        response.put("taskId", taskId);
        return response;
    }

    public Map<String, Object> report(String runId) {
        String pattern = UPLOAD_PREFIX + runId + ":%";
        List<TaskTiming> rows = jdbcTemplate.query("""
                        SELECT t.task_id, t.status AS task_status,
                               t.created_at AS task_created_at, t.started_at, t.completed_at,
                               o.status AS outbox_status, o.created_at AS outbox_created_at,
                               o.sent_at, o.send_attempt_count
                        FROM analysis_task t
                        LEFT JOIN task_outbox o
                          ON o.task_id = t.task_id
                         AND o.event_type = 'TASK_EXECUTE'
                         AND o.business_retry_no = t.retry_count
                        WHERE t.upload_id LIKE ?
                        ORDER BY t.id
                        """,
                (rs, rowNum) -> new TaskTiming(
                        rs.getString("task_id"),
                        rs.getString("task_status"),
                        toLocalDateTime(rs.getTimestamp("task_created_at")),
                        toLocalDateTime(rs.getTimestamp("started_at")),
                        toLocalDateTime(rs.getTimestamp("completed_at")),
                        rs.getString("outbox_status"),
                        toLocalDateTime(rs.getTimestamp("outbox_created_at")),
                        toLocalDateTime(rs.getTimestamp("sent_at")),
                        rs.getInt("send_attempt_count")),
                pattern);

        Map<String, Long> taskStatuses = countBy(rows, TaskTiming::taskStatus);
        Map<String, Long> outboxStatuses = countBy(rows, TaskTiming::outboxStatus);
        long terminal = rows.stream().filter(row -> isTerminal(row.taskStatus())).count();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("total", rows.size());
        report.put("terminal", terminal);
        report.put("taskStatuses", taskStatuses);
        report.put("outboxStatuses", outboxStatuses);
        report.put("outboxRetryCount", rows.stream().mapToInt(TaskTiming::sendAttemptCount).sum());
        report.put("maxOutboxRetryCount", rows.stream().mapToInt(TaskTiming::sendAttemptCount).max().orElse(0));
        report.put("timingsMs", timingReport(rows));
        report.put("completionThroughputPerSecond", completionThroughput(rows));
        return report;
    }

    private Map<String, Object> timingReport(List<TaskTiming> rows) {
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("outboxDispatch", summarize(durations(rows,
                TaskTiming::outboxCreatedAt, TaskTiming::sentAt)));
        timings.put("kafkaQueue", summarize(durations(rows,
                TaskTiming::sentAt, TaskTiming::startedAt)));
        timings.put("processing", summarize(durations(rows,
                TaskTiming::startedAt, TaskTiming::completedAt)));
        timings.put("endToEnd", summarize(durations(rows,
                TaskTiming::taskCreatedAt, TaskTiming::completedAt)));
        return timings;
    }

    private List<Long> durations(List<TaskTiming> rows,
                                 Function<TaskTiming, LocalDateTime> start,
                                 Function<TaskTiming, LocalDateTime> end) {
        List<Long> values = new ArrayList<>();
        for (TaskTiming row : rows) {
            LocalDateTime startAt = start.apply(row);
            LocalDateTime endAt = end.apply(row);
            if (startAt != null && endAt != null) {
                values.add(Math.max(0, Duration.between(startAt, endAt).toMillis()));
            }
        }
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private Map<String, Object> summarize(List<Long> sorted) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("samples", sorted.size());
        if (sorted.isEmpty()) {
            return summary;
        }
        summary.put("min", sorted.get(0));
        summary.put("avg", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        summary.put("p50", percentile(sorted, 0.50));
        summary.put("p95", percentile(sorted, 0.95));
        summary.put("p99", percentile(sorted, 0.99));
        summary.put("max", sorted.get(sorted.size() - 1));
        return summary;
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double completionThroughput(List<TaskTiming> rows) {
        LocalDateTime firstCreated = rows.stream()
                .map(TaskTiming::taskCreatedAt)
                .filter(value -> value != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime lastCompleted = rows.stream()
                .map(TaskTiming::completedAt)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        long completed = rows.stream().filter(row -> row.completedAt() != null).count();
        if (firstCreated == null || lastCompleted == null || completed == 0) {
            return 0;
        }
        long elapsedMs = Math.max(1, Duration.between(firstCreated, lastCompleted).toMillis());
        return Math.round(completed * 100_000.0 / elapsedMs) / 100.0;
    }

    private Map<String, Long> countBy(List<TaskTiming> rows, Function<TaskTiming, String> classifier) {
        return rows.stream()
                .map(classifier)
                .map(value -> value == null ? "MISSING" : value)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "DEAD".equals(status)
                || "CANCELLED".equals(status);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record TaskTiming(String taskId,
                              String taskStatus,
                              LocalDateTime taskCreatedAt,
                              LocalDateTime startedAt,
                              LocalDateTime completedAt,
                              String outboxStatus,
                              LocalDateTime outboxCreatedAt,
                              LocalDateTime sentAt,
                              int sendAttemptCount) {
    }
}
