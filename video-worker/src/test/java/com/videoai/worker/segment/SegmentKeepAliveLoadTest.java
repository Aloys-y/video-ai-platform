package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 连续多波片段使用同一个生产执行器，比较空闲回收时间；不访问模型或数据库。 */
@EnabledIfSystemProperty(named = "segment.keepalive.load", matches = "true")
class SegmentKeepAliveLoadTest {
    private static final int SCALE = 120;
    private static final int[] GAPS_SECONDS = {0, 15, 90, 240};

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void repeatedWaves() throws Exception {
        var results = new ArrayList<Map<String, Object>>();
        var json = new ObjectMapper();
        Path output = Path.of(System.getProperty("segment.keepalive.output", "target/segment-keepalive-load.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        int[] candidates = Arrays.stream(System.getProperty("segment.keepalive.candidates", "30,60,300").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int repeats = Integer.getInteger("segment.keepalive.repeats", 2);
        assertTrue(candidates.length > 0 && repeats > 0);
        for (int repeat = 0; repeat < repeats; repeat++) {
            long seed = 2026090910L + repeat;
            long[][][] samples = sample(seed);
            for (int n = 0; n < candidates.length; n++) {
                int keepAlive = candidates[(n + repeat) % candidates.length];
                results.add(run(repeat + 1, seed, samples, keepAlive));
                json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
                System.out.println("KEEPALIVE_PROGRESS " + results.size() + "/" + (repeats * candidates.length));
            }
        }
    }

    private long[][][] sample(long seed) {
        var random = new Random(seed);
        long[][][] result = new long[4][3][8];
        for (int w = 0; w < 4; w++) for (int p = 0; p < 3; p++) for (int s = 0; s < 8; s++) {
            double minutes;
            do { minutes = 7 + 2 * random.nextGaussian(); } while (minutes < 3 || minutes > 12);
            result[w][p][s] = Math.round(minutes * 60_000 / SCALE);
        }
        return result;
    }

    private Map<String, Object> run(int repeat, long seed, long[][][] samples, int keepAlive) throws Exception {
        var config = new SegmentAnalysisProperties();
        config.setThreads(4); config.setQueueCapacity(16);
        config.setRequestIntervalMs(8); config.setCancellationGraceMs(1000);
        var parents = Executors.newFixedThreadPool(3);
        var names = ConcurrentHashMap.<String>newKeySet();
        var calls = ConcurrentHashMap.<String>newKeySet();
        var saved = ConcurrentHashMap.<String>newKeySet();
        var waves = new ArrayList<Map<String, Object>>();
        long sequenceStart = System.nanoTime();
        try (var executor = new SegmentAnalysisExecutor(config, 8, keepAlive * 1000L / SCALE)) {
            for (int w = 0; w < 4; w++) {
                Thread.sleep(GAPS_SECONDS[w] * 1000L / SCALE);
                final int wave = w;
                int poolBefore = executor.poolSizeForTest(), namesBefore = names.size();
                var gate = new CountDownLatch(1); var readyGate = new CountDownLatch(3);
                var active = new AtomicInteger(); var peak = new AtomicInteger();
                var details = new CopyOnWriteArrayList<Map<String, Object>>();
                var parentTimes = new CopyOnWriteArrayList<Long>();
                var futures = new ArrayList<Future<?>>();
                long[] start = {0};
                for (int p = 0; p < 3; p++) {
                    final int parent = p;
                    futures.add(parents.submit(() -> {
                        readyGate.countDown(); gate.await();
                        long ready = System.nanoTime();
                        var work = new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
                        for (int s = 0; s < 8; s++) {
                            final int segment = s;
                            work.add(scope -> {
                                String id = wave + ":" + parent + ":" + segment;
                                assertTrue(calls.add(id));
                                names.add(Thread.currentThread().getName());
                                executor.awaitRequestPermit(scope);
                                long request = System.nanoTime();
                                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                                try {
                                    Thread.sleep(samples[wave][parent][segment]);
                                    scope.write(() -> { assertTrue(saved.add(id)); return null; });
                                    details.add(Map.of("parent", parent, "segment", segment,
                                            "plannedModelMs", samples[wave][parent][segment],
                                            "readyToRequestMs", ms(request - ready),
                                            "requestAtMs", ms(request - start[0]),
                                            "endAtMs", ms(System.nanoTime() - start[0])));
                                    return segment;
                                } finally { active.decrementAndGet(); }
                            });
                        }
                        assertEquals(8, executor.execute(work, Instant.now().plusSeconds(90), () -> false, v -> {}).size());
                        parentTimes.add(ms(System.nanoTime() - ready));
                        return null;
                    }));
                }
                assertTrue(readyGate.await(5, TimeUnit.SECONDS));
                start[0] = System.nanoTime(); gate.countDown();
                var timeline = new ArrayList<Map<String, Object>>();
                while (futures.stream().anyMatch(f -> !f.isDone())) {
                    var stats = executor.stats();
                    timeline.add(Map.of("atMs", ms(System.nanoTime() - start[0]),
                            "poolSize", executor.poolSizeForTest(), "activeWorkers", stats.active(),
                            "activeCalls", active.get(), "queued", stats.queued()));
                    Thread.sleep(20);
                }
                for (var future : futures) future.get();
                long elapsed = ms(System.nanoTime() - start[0]);
                assertEquals(24, details.size()); assertEquals(0, active.get());
                assertEquals(0, executor.stats().queued()); assertTrue(peak.get() <= 8);
                var waits = details.stream().map(d -> ((Number) d.get("readyToRequestMs")).longValue()).sorted().toList();
                var row = new LinkedHashMap<String, Object>();
                row.put("wave", w + 1); row.put("gapBeforeSecondsUnscaled", GAPS_SECONDS[w]);
                row.put("poolBefore", poolBefore); row.put("poolAfter", executor.poolSizeForTest());
                row.put("newWorkerNames", names.size() - namesBefore); row.put("peakCalls", peak.get());
                row.put("elapsedMs", elapsed); row.put("readyToRequestP95Ms", waits.get(22));
                row.put("parentCompletionMs", parentTimes); row.put("segments", details); row.put("timeline", timeline);
                waves.add(row);
                System.out.println("KEEPALIVE_WAVE repeat=" + repeat + " keepAlive=" + keepAlive
                        + " wave=" + (w + 1) + " before=" + poolBefore + " after=" + executor.poolSizeForTest()
                        + " newWorkers=" + (names.size() - namesBefore) + " elapsedMs=" + elapsed);
            }
            assertEquals(96, calls.size()); assertEquals(96, saved.size());
            var result = new LinkedHashMap<String, Object>();
            result.put("repeat", repeat); result.put("seed", seed); result.put("scale", SCALE);
            result.put("core", 4); result.put("maximum", 8); result.put("queueCapacity", 16);
            result.put("keepAliveSecondsUnscaled", keepAlive); result.put("keepAliveMs", keepAlive * 1000L / SCALE);
            result.put("requestIntervalMs", 8); result.put("uniqueWorkerNames", names.size());
            result.put("sequenceElapsedMs", ms(System.nanoTime() - sequenceStart)); result.put("waves", waves);
            return result;
        } finally { parents.shutdownNow(); assertTrue(parents.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
}
