package com.videoai.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.ExecutionOwnership;
import com.videoai.common.message.TaskMessage;
import com.videoai.worker.ownership.TaskLeaseService;
import com.videoai.worker.processor.TaskProcessor;
import com.videoai.worker.segment.SegmentAnalysisExecutor;
import com.videoai.worker.segment.SegmentAnalysisProperties;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 显式启用的远端 Kafka 编排压测。Topic/Group 独立；生产消费入口、两个线程池及租约服务真实执行。
 * 媒体/AI 由合成工作替代，数据库使用隔离 H2，不代表 MySQL/OSS/模型完整链路验收。
 */
@EnabledIfSystemProperty(named = "kafka.async.load", matches = "true")
class KafkaAsyncLoadTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test @Timeout(900)
    void realBrokerMatrix() throws Exception {
        String bootstrap = Objects.requireNonNull(System.getProperty("kafka.load.bootstrap"), "必须显式指定压测 Broker");
        // 仅调整日志级别，不修改生产配置或正在运行的服务。
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var oldLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var reports = new ArrayList<Map<String, Object>>();
        Path output = Path.of(System.getProperty("kafka.load.output", "target/kafka-async-load.json"));
        try {
            for (var scenario : List.of(new Scenario("baseline", 6, 16, false, false),
                    new Scenario("burst", 30, 16, false, true),
                    new Scenario("failure-small-queue", 6, 4, true, false))) {
                reports.add(run(bootstrap, scenario));
                Files.createDirectories(output.toAbsolutePath().getParent());
                json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), reports);
            }
        } finally { logger.setLevel(oldLevel); }
    }

    private record Scenario(String name, int videos, int queue, boolean failures, boolean duplicates) {}

    private Map<String, Object> run(String bootstrap, Scenario scenario) throws Exception {
        String topic = "codex-async-load-" + UUID.randomUUID();
        String group = topic + "-group";
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + topic + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        // Keep one connection open for the duration of the scenario; closing it destroys this test DB.
        try (var keeper = ds.getConnection();
             var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000,
                     AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000))) {
            admin.describeCluster().nodes().get(20, TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1)
                    .configs(Map.of("retention.ms", "3600000")))).all().get(20, TimeUnit.SECONDS);
            try { return exercise(bootstrap, topic, group, scenario, new JdbcTemplate(ds), ds); }
            finally {
                // Only delete the unique topic/group created by this scenario.
                try { admin.deleteConsumerGroups(List.of(group)).all().get(15, TimeUnit.SECONDS); }
                catch (Exception e) { System.out.println("LOAD cleanup group: " + e.getClass().getSimpleName()); }
                try { admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS); }
                catch (Exception e) { System.out.println("LOAD cleanup topic " + topic + ": " + e.getClass().getSimpleName()); }
            }
        }
    }

    private Map<String, Object> exercise(String bootstrap, String topic, String group, Scenario scenario,
                                        JdbcTemplate jdbc, javax.sql.DataSource ds) throws Exception {
        jdbc.execute("CREATE TABLE analysis_task(task_id VARCHAR(64) PRIMARY KEY,retry_count INT,status VARCHAR(32),execution_owner VARCHAR(64),execution_lease_until TIMESTAMP,execution_heartbeat_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE analysis_segment(task_id VARCHAR(64),segment_no INT,status VARCHAR(32),PRIMARY KEY(task_id,segment_no))");
        for (int i = 0; i < scenario.videos; i++)
            jdbc.update("INSERT INTO analysis_task(task_id,retry_count,status) VALUES (?,0,'QUEUED')", "task-" + i);
        var leases = new TaskLeaseService(jdbc, new DataSourceTransactionManager(ds));
        var config = new SegmentAnalysisProperties();
        config.setQueueCapacity(scenario.queue); // Production 16; failure scenario deliberately forces saturation at 4.
        var processor = mock(TaskProcessor.class);
        var delivered = new AtomicInteger();
        var acked = new AtomicInteger();
        var earlyAck = new AtomicInteger();
        var activeCalls = new AtomicInteger();
        var peakCalls = new AtomicInteger();
        var assignments = new AtomicInteger();
        var successes = new AtomicInteger();
        var failures = new AtomicInteger();
        var commitFault = new AtomicBoolean();
        var ids = ConcurrentHashMap.<String>newKeySet();
        var duplicateCalls = new AtomicInteger();
        var parentStarts = new ConcurrentHashMap<String, Long>();
        var parentEnds = new ConcurrentHashMap<String, Long>();
        var published = new ConcurrentHashMap<String, Long>();
        var workerWaits = new CopyOnWriteArrayList<Long>();
        var modelStarts = new CopyOnWriteArrayList<Long>();
        var partitionTasks = new HashMap<TopicPartition, List<String>>();
        for (int p = 0; p < 3; p++) partitionTasks.put(new TopicPartition(topic, p), new ArrayList<>());

        try (var segments = new SegmentAnalysisExecutor(config);
             var coordinator = new AsyncVideoCoordinator(processor, leases)) {
            when(processor.process(any())).thenAnswer(invocation -> {
                String task = invocation.<TaskMessage>getArgument(0).getTaskId();
                int video = Integer.parseInt(task.substring(5));
                parentStarts.put(task, System.nanoTime());
                var token = ExecutionOwnership.current();
                leases.fenced(token, () -> {
                    jdbc.update("UPDATE analysis_task SET status='PROCESSING' WHERE task_id=?", task);
                    for (int s = 0; s < 5; s++) jdbc.update("INSERT INTO analysis_segment VALUES (?,?,'PREPARED')", task, s);
                    return null;
                });
                var work = new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
                for (int s = 0; s < 5; s++) {
                    final int segment = s;
                    work.add(scope -> {
                        if (!ids.add(task + ":" + segment)) duplicateCalls.incrementAndGet();
                        workerWaits.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - parentStarts.get(task)));
                        segments.awaitRequestPermit(scope);
                        scope.write(() -> leases.fenced(ExecutionOwnership.current(), () ->
                                jdbc.update("UPDATE analysis_segment SET status='PROCESSING' WHERE task_id=? AND segment_no=?", task, segment)));
                        modelStarts.add(System.nanoTime());
                        peakCalls.accumulateAndGet(activeCalls.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(new long[]{3000, 7000, 10000}[(video + segment) % 3]);
                            boolean fail = scenario.failures && segment == 2;
                            scope.write(() -> leases.fenced(ExecutionOwnership.current(), () ->
                                    jdbc.update("UPDATE analysis_segment SET status=? WHERE task_id=? AND segment_no=?", fail ? "FAILED" : "SUCCEEDED", task, segment)));
                            if (fail) { failures.incrementAndGet(); throw new java.io.IOException("模拟片段失败"); }
                            successes.incrementAndGet();
                            return segment;
                        } finally { activeCalls.decrementAndGet(); }
                    });
                }
                String terminal = "COMPLETED";
                try { segments.execute(work, Instant.MAX, () -> false, value -> {}); }
                catch (SegmentAnalysisExecutor.BatchFailure e) {
                    if (!scenario.failures || !e.converged() || e.persistenceUnsettled()) throw new IllegalStateException("意外批次失败", e);
                    terminal = "PARTIALLY_COMPLETED";
                }
                String state = terminal;
                leases.fenced(token, () -> jdbc.update("UPDATE analysis_task SET status=? WHERE task_id=?", state, task));
                parentEnds.put(task, System.nanoTime());
                if (scenario.failures && video == 0 && commitFault.compareAndSet(false, true))
                    throw new org.springframework.transaction.TransactionSystemException("模拟终态已提交但客户端收到异常");
                return true;
            });
            var entry = new TaskConsumer(processor);
            ReflectionTestUtils.setField(entry, "coordinator", coordinator);
            ReflectionTestUtils.setField(entry, "asyncEnabled", true);
            var props = new HashMap<String, Object>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 10000); // Deliberately less than a video's duration.
            props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            var cp = new ContainerProperties(topic);
            cp.setAckMode(ContainerProperties.AckMode.MANUAL);
            cp.setAsyncAcks(true);
            cp.setPollTimeout(1000);
            cp.setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
                @Override public void onPartitionsAssigned(Consumer<?, ?> c, Collection<TopicPartition> ps) { if (!ps.isEmpty()) assignments.incrementAndGet(); }
                @Override public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> c, Collection<TopicPartition> ps) { coordinator.revoke(c, ps); }
                @Override public void onPartitionsLost(Consumer<?, ?> c, Collection<TopicPartition> ps) { coordinator.revoke(c, ps); }
            });
            cp.setMessageListener((AcknowledgingConsumerAwareMessageListener<String, String>) (r, ack, consumer) -> {
                delivered.incrementAndGet();
                var message = TaskMessage.builder().taskId(r.value()).businessRetryNo(0).build();
                entry.consumeRecord(new ConsumerRecord<>(r.topic(), r.partition(), r.offset(), r.key(), message), () -> {
                    if (!leases.settled(r.value(), 0)) earlyAck.incrementAndGet();
                    ack.acknowledge();
                    acked.incrementAndGet();
                }, consumer);
            });
            var container = new ConcurrentMessageListenerContainer<>(new DefaultKafkaConsumerFactory<String, String>(props), cp);
            container.setConcurrency(3);
            container.setBeanName("isolated-async-load");
            container.setCommonErrorHandler(new com.videoai.worker.config.KafkaListenerConfig().unsettledTaskErrorHandler());
            var producerProps = new HashMap<String, Object>();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
            producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15000);
            try (var producer = new KafkaProducer<String, String>(producerProps, new StringSerializer(), new StringSerializer());
                 var observer = new KafkaConsumer<String, String>(props)) {
                container.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
                while (System.nanoTime() < until && (container.getAssignedPartitions() == null || container.getAssignedPartitions().size() != 3)) Thread.sleep(100);
                assertNotNull(container.getAssignedPartitions());
                assertEquals(3, container.getAssignedPartitions().size(), "真实集群必须完成分区分配");
                int initialAssignments = assignments.get();
                long began = System.nanoTime();
                for (int i = 0; i < scenario.videos; i++) {
                    String task = "task-" + i;
                    published.put(task, System.nanoTime());
                    producer.send(new ProducerRecord<>(topic, i % 3, task, task)).get(15, TimeUnit.SECONDS);
                    partitionTasks.get(new TopicPartition(topic, i % 3)).add(task);
                }
                if (scenario.duplicates) for (int i = 0; i < scenario.videos; i += 10) {
                    String task = "task-" + i;
                    producer.send(new ProducerRecord<>(topic, i % 3, task, task)).get(15, TimeUnit.SECONDS);
                    partitionTasks.get(new TopicPartition(topic, i % 3)).add(task);
                }
                int records = partitionTasks.values().stream().mapToInt(List::size).sum();
                int peakQueue = 0, peakVideos = 0, peakInFlight = 0, peakVideoQueue = 0;
                double maxLastPollSeconds = 0;
                boolean fullyCommitted = false;
                long nextLog = 0, nextOffsetCheck = 0;
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(420);
                System.out.println("LOAD start " + scenario.name + " videos=" + scenario.videos + " records=" + records);
                while (System.nanoTime() < until) {
                    var vs = coordinator.stats(); var ss = segments.stats();
                    peakQueue = Math.max(peakQueue, ss.queued()); peakVideos = Math.max(peakVideos, vs.running());
                    peakInFlight = Math.max(peakInFlight, vs.inFlight()); peakVideoQueue = Math.max(peakVideoQueue, vs.queued());
                    for (var metrics : container.metrics().values()) for (var metric : metrics.entrySet())
                        if (metric.getKey().name().equals("last-poll-seconds-ago"))
                            maxLastPollSeconds = Math.max(maxLastPollSeconds, ((Number) metric.getValue().metricValue()).doubleValue());
                    if (System.nanoTime() >= nextOffsetCheck) {
                        var offsets = observer.committed(partitionTasks.keySet(), Duration.ofSeconds(10));
                        fullyCommitted = true;
                        for (var part : partitionTasks.entrySet()) {
                            var offset = offsets.get(part.getKey()); long position = offset == null ? 0 : offset.offset();
                            for (int n = 0; n < Math.min(position, part.getValue().size()); n++)
                                assertTrue(leases.settled(part.getValue().get(n), 0), "提交位点不得越过未完成任务");
                            fullyCommitted &= position == part.getValue().size();
                        }
                        nextOffsetCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    }
                    if (fullyCommitted && vs.inFlight() == 0 && vs.running() == 0 && ss.active() == 0 && ss.queued() == 0) break;
                    if (System.nanoTime() >= nextLog) {
                        System.out.println("LOAD progress " + scenario.name + " parents=" + parentEnds.size() + "/" + scenario.videos + " segments=" + (successes.get() + failures.get()) + " queued=" + ss.queued() + " ack=" + acked.get());
                        nextLog = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    }
                    Thread.sleep(50);
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                assertTrue(fullyCommitted, "所有位点必须最终提交");
                assertEquals(scenario.videos, parentEnds.size());
                assertEquals(records, delivered.get()); assertEquals(records, acked.get()); assertEquals(0, earlyAck.get());
                assertEquals(scenario.videos * 5, ids.size()); assertEquals(0, duplicateCalls.get());
                assertEquals(scenario.failures ? scenario.videos : 0, failures.get());
                assertEquals(scenario.videos * 5, successes.get() + failures.get());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_segment WHERE status IN ('PREPARED','PROCESSING')", Integer.class));
                assertEquals(initialAssignments, assignments.get(), "正常负载不得触发额外重平衡");
                assertTrue(peakCalls.get() <= 4); assertTrue(peakVideos <= 3); assertTrue(peakInFlight <= 3);
                assertTrue(peakQueue <= scenario.queue); assertTrue(maxLastPollSeconds < 10, "长任务期间仍须持续poll");
                if (scenario.failures) { assertTrue(commitFault.get()); assertTrue(segments.stats().saturatedBatches() > 0); }
                var endToEnd = new ArrayList<Long>(); var processing = new ArrayList<Long>(); var kafkaWait = new ArrayList<Long>();
                for (String task : parentEnds.keySet()) {
                    endToEnd.add(TimeUnit.NANOSECONDS.toMillis(parentEnds.get(task) - published.get(task)));
                    processing.add(TimeUnit.NANOSECONDS.toMillis(parentEnds.get(task) - parentStarts.get(task)));
                    kafkaWait.add(TimeUnit.NANOSECONDS.toMillis(parentStarts.get(task) - published.get(task)));
                }
                assertTrue(processing.stream().anyMatch(ms -> ms > 10000), "必须实际覆盖视频处理超过poll期限的场景");
                long minGap = Long.MAX_VALUE;
                var sortedStarts = modelStarts.stream().sorted().toList();
                for (int n = 1; n < sortedStarts.size(); n++) minGap = Math.min(minGap, sortedStarts.get(n) - sortedStarts.get(n - 1));
                assertTrue(minGap >= TimeUnit.MILLISECONDS.toNanos(950), "每秒一次请求节奏应保持");
                var report = new LinkedHashMap<String, Object>();
                report.put("scenario", scenario.name); report.put("topic", topic); report.put("videos", scenario.videos);
                report.put("segments", ids.size()); report.put("messages", records); report.put("acked", acked.get());
                report.put("elapsedMs", elapsed); report.put("succeededSegments", successes.get()); report.put("failedSegments", failures.get());
                report.put("terminalStates", jdbc.queryForList("SELECT status,COUNT(*) AS total FROM analysis_task GROUP BY status"));
                report.put("peakModelCalls", peakCalls.get()); report.put("peakVideoThreads", peakVideos); report.put("peakInFlight", peakInFlight);
                report.put("segmentQueueCapacity", scenario.queue); report.put("peakSegmentQueueSampled", peakQueue); report.put("peakVideoQueueSampled", peakVideoQueue);
                report.put("saturatedBatches", segments.stats().saturatedBatches()); report.put("maxLastPollSecondsSampled", maxLastPollSeconds);
                report.put("unexpectedReassignments", assignments.get() - initialAssignments); report.put("renewalFailures", leases.renewalFailures());
                report.put("duplicateModelCalls", duplicateCalls.get()); report.put("earlyAcks", earlyAck.get()); report.put("fullyCommitted", fullyCommitted);
                report.put("endToEndMs", summary(endToEnd)); report.put("videoProcessingMs", summary(processing));
                report.put("kafkaWaitMs", summary(kafkaWait)); report.put("segmentReadyToWorkerMs", summary(workerWaits));
                report.put("minRequestGapMs", TimeUnit.NANOSECONDS.toMillis(minGap));
                System.out.println("LOAD result " + json.writeValueAsString(report));
                return report;
            } finally { container.stop(); }
        }
    }

    private Map<String, Long> summary(List<Long> samples) {
        var values = samples.stream().sorted().toList();
        return Map.of("p50", values.get((int) Math.ceil(values.size() * .5) - 1),
                "p95", values.get((int) Math.ceil(values.size() * .95) - 1), "max", values.get(values.size() - 1));
    }
}
