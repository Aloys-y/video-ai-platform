package com.videoai.worker.segment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 1:60时间缩放；18组串行执行，避免不同配置竞争CPU影响对照。 */
@EnabledIfSystemProperty(named="segment.queue.load",matches="true")
class SegmentQueueCapacityTest {
 @Test @Timeout(value=25,unit=TimeUnit.MINUTES)
 void matrix() throws Exception {
  var rows=new ArrayList<Map<String,Object>>();var json=new ObjectMapper();
  Path output=Path.of(System.getProperty("segment.queue.output","target/queue-capacity.json"));Files.createDirectories(output.toAbsolutePath().getParent());
  for(int repeat=0;repeat<3;repeat++) for(boolean stagger:new boolean[]{false,true}) {
   // 轮换顺序，避免固定先后顺序偏差。
   for(int i=0;i<3;i++) {
    int queue=new int[]{16,32,64}[(i+repeat)%3];
    rows.add(run(queue,stagger,repeat));
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),rows);
    System.out.println("QUEUE_LOAD completed="+rows.size()+"/18 queue="+queue+" scenario="+(stagger?"large-first":"burst"));
   }
  }
 }
 private Map<String,Object> run(int queue,boolean stagger,int repeat) throws Exception {
  var config=new SegmentAnalysisProperties();config.setQueueCapacity(queue);config.setRequestIntervalMs(17);
  var callers=Executors.newFixedThreadPool(3);var gate=new CountDownLatch(1);
  var peakQueue=new AtomicInteger();var active=new AtomicInteger();var peakActive=new AtomicInteger();
  var peakHeap=new AtomicLong();var completed=new AtomicInteger();var ids=ConcurrentHashMap.<String>newKeySet();
  var reports=new CopyOnWriteArrayList<Map<String,Object>>();
  var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
  var process=os instanceof com.sun.management.OperatingSystemMXBean x?x:null;
  long cpuBefore=process==null?0:process.getProcessCpuTime();long began=System.nanoTime();
  try(var executor=new SegmentAnalysisExecutor(config)) {
   var futures=new ArrayList<Future<?>>();
   for(int p=0;p<3;p++) {final int parent=p;final int count=stagger?(p==0?30:3):12;
    futures.add(callers.submit(()->{
     gate.await();if(stagger && parent>0)Thread.sleep(500);long ready=System.nanoTime();
     long[] accepted=new long[count],started=new long[count],requested=new long[count];
     var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
     for(int j=0;j<count;j++) {final int index=j;work.add(scope->{
      assertTrue(ids.add(parent+"-"+index));started[index]=System.nanoTime();executor.awaitRequestPermit(scope);requested[index]=System.nanoTime();
      peakActive.accumulateAndGet(active.incrementAndGet(),Math::max);
      try {Thread.sleep(new long[]{3000,7000,10000}[index%3]);scope.check();return index;}
      finally {active.decrementAndGet();}
     });}
     var result=executor.execute(work,Instant.now().plusSeconds(100),()->false,v->completed.incrementAndGet(),(index,time)->accepted[index]=time);
     assertEquals(count,result.size());
     var admission=new ArrayList<Long>();var queued=new ArrayList<Long>();var rate=new ArrayList<Long>();
     for(int j=0;j<count;j++) {
      assertTrue(accepted[j]>0 && started[j]>0 && requested[j]>0);
      admission.add(TimeUnit.NANOSECONDS.toMillis(accepted[j]-ready));
      queued.add(TimeUnit.NANOSECONDS.toMillis(Math.max(0,started[j]-accepted[j])));
      rate.add(TimeUnit.NANOSECONDS.toMillis(requested[j]-started[j]));
     }
     var report=new LinkedHashMap<String,Object>();report.put("parent",parent);report.put("segments",count);
     report.put("completionMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-ready));
     report.put("firstModelStartMs",TimeUnit.NANOSECONDS.toMillis(Arrays.stream(requested).min().orElseThrow()-ready));
     report.put("admissionWaitMs",stats(admission));report.put("queueWaitMs",stats(queued));report.put("rateWaitMs",stats(rate));reports.add(report);return null;
    }));
   }
   gate.countDown();
   while(futures.stream().anyMatch(f->!f.isDone())) {
    peakQueue.accumulateAndGet(executor.stats().queued(),Math::max);
    peakHeap.accumulateAndGet(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),Math::max);Thread.sleep(10);
   }
   for(var f:futures)f.get();
   assertEquals(36,completed.get());assertEquals(36,ids.size());assertTrue(peakActive.get()<=4);assertTrue(peakQueue.get()<=queue);
   assertEquals(0,executor.stats().queued());assertEquals(0,active.get());
   var r=new LinkedHashMap<String,Object>();r.put("queue",queue);r.put("scenario",stagger?"large-first":"burst");r.put("repeat",repeat+1);
   r.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));r.put("peakQueueSampled",peakQueue.get());r.put("peakModelCalls",peakActive.get());
   r.put("saturatedBatches",executor.stats().saturatedBatches());r.put("heapUsedPeakBytes",peakHeap.get());
   r.put("processCpuMs",process==null?null:TimeUnit.NANOSECONDS.toMillis(process.getProcessCpuTime()-cpuBefore));
   r.put("parents",reports.stream().sorted(Comparator.comparingInt(x->(Integer)x.get("parent"))).toList());return r;
  } finally {gate.countDown();callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
 }
 private Map<String,Long> stats(List<Long> values) {
  var s=values.stream().sorted().toList();return Map.of("p50",s.get((int)Math.ceil(s.size()*.5)-1),"p95",s.get((int)Math.ceil(s.size()*.95)-1),"max",s.get(s.size()-1));
 }
}
