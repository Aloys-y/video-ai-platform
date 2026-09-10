package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用生产执行器模拟截断正态耗时：固定样本，不同线程池参数配对比较。 */
@EnabledIfSystemProperty(named="segment.normal.load",matches="true")
class SegmentNormalLoadTest {
    private static final int PARENTS=3, SEGMENTS=8;
    private record Shape(int core,int maximum,int queue) {}

    @Test @Timeout(value=20,unit=TimeUnit.MINUTES)
    void matrix() throws Exception {
        var shapes=List.of(new Shape(4,4,16),new Shape(4,8,16),new Shape(4,8,4),
                new Shape(8,8,16),new Shape(4,16,16));
        var reports=new ArrayList<Map<String,Object>>();
        var json=new ObjectMapper();
        Path output=Path.of(System.getProperty("segment.normal.output","target/segment-normal-load.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        for(int repeat=0;repeat<3;repeat++) {
            long seed=2026090900L+repeat;
            long[][] delays=sample(seed);
            // 相同种子同一组24个耗时，不同配置轮换执行顺序；每组新建池，比较冷启动行为。
            for(int n=0;n<shapes.size();n++) {
                Shape shape=shapes.get((n+repeat)%shapes.size());
                reports.add(run("burst",repeat+1,seed,delays,shape,new long[]{0,0,0}));
                json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),reports);
                System.out.println("NORMAL_PROGRESS "+reports.size()+"/18");
            }
        }
        // 补充错峰到达：与第一轮完全相同的模型耗时，视频在0/3/6秒准备好；单次探索不作统计结论。
        for(Shape shape:List.of(shapes.get(0),shapes.get(2),shapes.get(3))) {
            reports.add(run("staggered",1,2026090900L,sample(2026090900L),shape,new long[]{0,3000,6000}));
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),reports);
            System.out.println("NORMAL_PROGRESS "+reports.size()+"/18");
        }
    }

    private long[][] sample(long seed) {
        var random=new Random(seed);long[][] values=new long[PARENTS][SEGMENTS];
        for(int p=0;p<PARENTS;p++)for(int s=0;s<SEGMENTS;s++) {
            double minutes;
            do {minutes=7+2*random.nextGaussian();} while(minutes<3||minutes>12);
            values[p][s]=Math.round(minutes*1000); // 1分钟→1秒，重采样截断，不把尾部堆到边界。
        }
        return values;
    }

    private Map<String,Object> run(String scenario,int repeat,long seed,long[][] delays,Shape shape,long[] arrivalMs) throws Exception {
        var config=new SegmentAnalysisProperties();config.setThreads(shape.core);config.setQueueCapacity(shape.queue);
        config.setRequestIntervalMs(17);config.setCancellationGraceMs(1000);
        var parents=Executors.newFixedThreadPool(PARENTS);
        var gate=new CountDownLatch(1);var readyGate=new CountDownLatch(PARENTS);
        var ids=ConcurrentHashMap.<String>newKeySet();var saved=ConcurrentHashMap.<String>newKeySet();
        var parentRows=new CopyOnWriteArrayList<Map<String,Object>>();
        var activeCalls=new AtomicInteger();var peakCalls=new AtomicInteger();
        var os=ManagementFactory.getOperatingSystemMXBean();
        var process=os instanceof com.sun.management.OperatingSystemMXBean p?p:null;
        long cpuBefore=process==null?0:process.getProcessCpuTime();
        long[] batchStart={0};
        try(var executor=new SegmentAnalysisExecutor(config,shape.maximum,1000)) {
            var futures=new ArrayList<Future<?>>();
            for(int p=0;p<PARENTS;p++) {
                final int parent=p;
                futures.add(parents.submit(()->{
                    readyGate.countDown();gate.await();Thread.sleep(arrivalMs[parent]);long ready=System.nanoTime();
                    long[] accepted=new long[SEGMENTS],entered=new long[SEGMENTS],requested=new long[SEGMENTS],ended=new long[SEGMENTS];
                    var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
                    for(int s=0;s<SEGMENTS;s++) {
                        final int segment=s;
                        work.add(scope->{
                            entered[segment]=System.nanoTime();assertTrue(ids.add(parent+":"+segment),"不能重复执行");
                            executor.awaitRequestPermit(scope);requested[segment]=System.nanoTime();
                            peakCalls.accumulateAndGet(activeCalls.incrementAndGet(),Math::max);
                            try {
                                Thread.sleep(delays[parent][segment]);
                                scope.write(()->{assertTrue(saved.add(parent+":"+segment));return null;});return segment;
                            } finally {ended[segment]=System.nanoTime();activeCalls.decrementAndGet();}
                        });
                    }
                    var values=executor.execute(work,Instant.now().plusSeconds(150),()->false,v->{},(index,time)->accepted[index]=time);
                    assertEquals(SEGMENTS,values.size());
                    var detail=new ArrayList<Map<String,Object>>();
                    for(int s=0;s<SEGMENTS;s++) {
                        assertTrue(accepted[s]>0&&entered[s]>0&&requested[s]>0&&ended[s]>0);
                        var r=new LinkedHashMap<String,Object>();r.put("segment",s);r.put("plannedModelMs",delays[parent][s]);
                        r.put("admissionMs",ms(accepted[s]-ready));
                        r.put("queueMs",ms(Math.max(0,entered[s]-accepted[s])));
                        r.put("rateMs",ms(requested[s]-entered[s]));r.put("modelMs",ms(ended[s]-requested[s]));
                        r.put("readyToRequestMs",ms(requested[s]-ready));r.put("requestAtMs",ms(requested[s]-batchStart[0]));
                        r.put("endAtMs",ms(ended[s]-batchStart[0]));detail.add(r);
                    }
                    var row=new LinkedHashMap<String,Object>();row.put("parent",parent);row.put("arrivalMs",arrivalMs[parent]);
                    row.put("completionFromReadyMs",ms(System.nanoTime()-ready));
                    row.put("firstRequestFromReadyMs",ms(Arrays.stream(requested).min().orElseThrow()-ready));
                    row.put("segments",detail);parentRows.add(row);return null;
                }));
            }
            assertTrue(readyGate.await(5,TimeUnit.SECONDS));batchStart[0]=System.nanoTime();gate.countDown();
            int peakQueue=0,peakWorkers=0;long peakHeap=0;var timeline=new ArrayList<Map<String,Object>>();
            long nextLog=0;
            while(futures.stream().anyMatch(f->!f.isDone())) {
                var stats=executor.stats();long elapsed=ms(System.nanoTime()-batchStart[0]);
                peakQueue=Math.max(peakQueue,stats.queued());peakWorkers=Math.max(peakWorkers,stats.active());
                peakHeap=Math.max(peakHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
                timeline.add(Map.of("atMs",elapsed,"poolSize",executor.poolSizeForTest(),"activeWorkers",stats.active(),"activeCalls",activeCalls.get(),"queued",stats.queued(),"saved",saved.size()));
                if(elapsed>=nextLog) {
                    System.out.println("NORMAL_RUNNING "+scenario+" repeat="+repeat+" shape="+shape+" elapsedMs="+elapsed+" saved="+saved.size()+" queue="+stats.queued());nextLog=elapsed+30000;
                }
                Thread.sleep(50);
            }
            for(var future:futures)future.get();
            long elapsed=ms(System.nanoTime()-batchStart[0]);
            assertEquals(24,ids.size());assertEquals(24,saved.size());assertEquals(0,activeCalls.get());assertEquals(0,executor.stats().queued());
            assertTrue(peakCalls.get()<=shape.maximum);assertTrue(peakQueue<=shape.queue);
            long plannedTotal=Arrays.stream(delays).flatMapToLong(Arrays::stream).sum();
            var details=new ArrayList<Map<String,Object>>();for(var row:parentRows)details.addAll((List<Map<String,Object>>)row.get("segments"));
            var result=new LinkedHashMap<String,Object>();result.put("scenario",scenario);result.put("repeat",repeat);result.put("seed",seed);
            result.put("core",shape.core);result.put("maximum",shape.maximum);result.put("queueCapacity",shape.queue);
            result.put("keepAliveMs",1000);result.put("requestIntervalMs",17);result.put("scale",60);
            result.put("plannedDelaysMs",delays);result.put("plannedTotalModelMs",plannedTotal);
            result.put("elapsedMs",elapsed);result.put("throughputSegmentsPerSecond",24000.0/elapsed);
            result.put("largestPoolSize",executor.largestPoolSizeForTest());result.put("peakActiveWorkers",peakWorkers);result.put("peakModelCalls",peakCalls.get());
            result.put("peakQueueSampled",peakQueue);result.put("saturatedBatches",executor.stats().saturatedBatches());
            result.put("processCpuMs",process==null?null:ms(process.getProcessCpuTime()-cpuBefore));result.put("heapUsedPeakBytes",peakHeap);
            result.put("configuredCapacityBusyFraction",(double)plannedTotal/(shape.maximum*elapsed));
            for(String key:List.of("admissionMs","queueMs","rateMs","modelMs","readyToRequestMs"))
                result.put(key,summary(details.stream().map(r->((Number)r.get(key)).longValue()).toList()));
            result.put("parents",parentRows.stream().sorted(Comparator.comparingInt(r->(Integer)r.get("parent"))).toList());result.put("timeline",timeline);
            System.out.println("NORMAL_RESULT "+scenario+" repeat="+repeat+" shape="+shape+" elapsedMs="+elapsed+" largestPool="+executor.largestPoolSizeForTest()+" queuePeak="+peakQueue);
            return result;
        } finally {gate.countDown();parents.shutdownNow();assertTrue(parents.awaitTermination(3,TimeUnit.SECONDS));}
    }
    private static long ms(long nanos){return TimeUnit.NANOSECONDS.toMillis(nanos);}
    private static Map<String,Long> summary(List<Long> data){var values=data.stream().sorted().toList();return Map.of("p50",values.get(11),"p95",values.get(22),"max",values.get(23),"sum",values.stream().mapToLong(Long::longValue).sum());}
}
