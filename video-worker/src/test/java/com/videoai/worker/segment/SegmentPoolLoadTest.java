package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本地模拟AI：不启动Spring、不访问数据库/OSS/云端。默认不运行压测。 */
@EnabledIfSystemProperty(named="segment.load",matches="true")
class SegmentPoolLoadTest {
 @Test @org.junit.jupiter.api.Timeout(90)
 void matrix() throws Exception {
  var reports=new ArrayList<Map<String,Object>>();
  for(int threads:new int[]{1,2,4}) reports.add(run("baseline-"+threads,threads,16,3,40,1,false,false));
  reports.add(run("large-queue",4,128,3,40,1,false,false));
  reports.add(run("partial-failure",4,16,3,40,1,true,false));
  reports.add(run("deadline",4,16,3,40,1,false,true));
  reports.add(run("configured-rate",4,16,3,2,1000,false,false));
  Path output=Path.of(System.getProperty("segment.load.output","target/segment-load.json"));
  Files.createDirectories(output.toAbsolutePath().getParent());
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),reports);
 }
 private Map<String,Object> run(String name,int threads,int queue,int parents,int count,long interval,boolean faults,boolean timeout) throws Exception {
  var config=new SegmentAnalysisProperties();config.setThreads(threads);config.setQueueCapacity(queue);
  config.setRequestIntervalMs(interval);config.setCancellationGraceMs(500);
  var callers=Executors.newFixedThreadPool(parents);
  var waits=new CopyOnWriteArrayList<Long>();var starts=new CopyOnWriteArrayList<Long>();
  var latencies=new CopyOnWriteArrayList<Long>();var durations=new CopyOnWriteArrayList<Long>();
  var active=new AtomicInteger();var peak=new AtomicInteger();var done=new AtomicInteger();var errors=new AtomicInteger();
  var writes=new AtomicInteger();var failedParents=new AtomicInteger();var converged=new AtomicInteger();
  var ids=ConcurrentHashMap.<String>newKeySet();var gate=new CountDownLatch(1);
  var maxQueued=new AtomicInteger();long began=System.nanoTime();
  try(var executor=new SegmentAnalysisExecutor(config)) {
   var futures=new ArrayList<Future<?>>();
   for(int p=0;p<parents;p++) {final int parent=p;
    futures.add(callers.submit(()->{
     gate.await();long ready=System.nanoTime();
     var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
     for(int j=0;j<count;j++) {final int index=j;
      work.add(scope->{
       assertTrue(ids.add(parent+"-"+index),"不得重复执行");
       waits.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-ready));
       executor.awaitRequestPermit(scope);
       long start=System.nanoTime();starts.add(start);peak.accumulateAndGet(active.incrementAndGet(),Math::max);
       try {
        Thread.sleep(timeout?100:30+(index%4)*10);
        if(faults && index%10==0) {errors.incrementAndGet();throw new java.io.IOException("模拟AI失败");}
        scope.write(()->{writes.incrementAndGet();return null;});done.incrementAndGet();return index;
       } finally {active.decrementAndGet();latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
      });
     }
     try {executor.execute(work,Instant.now().plusMillis(timeout?180:30000),()->false,v->{});}
     catch(SegmentAnalysisExecutor.BatchFailure e) {failedParents.incrementAndGet();if(e.converged())converged.incrementAndGet();}
     durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-ready));return null;
    }));
   }
   gate.countDown();
   while(futures.stream().anyMatch(f->!f.isDone())) {
    maxQueued.accumulateAndGet(executor.stats().queued(),Math::max);Thread.sleep(2);
   }
   for(var f:futures) f.get(2,TimeUnit.SECONDS);
   long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
   assertTrue(peak.get()<=threads);assertTrue(maxQueued.get()<=queue);
   assertEquals(0,active.get());assertEquals(0,executor.stats().queued());
   if(timeout) {assertEquals(parents,failedParents.get());assertEquals(parents,converged.get());}
   else {assertEquals(parents*count,done.get()+errors.get());assertEquals(faults?parents:0,failedParents.get());}
   int saved=writes.get();Thread.sleep(30);assertEquals(saved,writes.get(),"结束后不得出现迟到写入");
   var sortedStarts=starts.stream().sorted().toList();long minGap=Long.MAX_VALUE;
   for(int i=1;i<sortedStarts.size();i++) minGap=Math.min(minGap,sortedStarts.get(i)-sortedStarts.get(i-1));
   if(interval==1000) assertTrue(minGap>=TimeUnit.MILLISECONDS.toNanos(950));
   var report=new LinkedHashMap<String,Object>();
   report.put("scenario",name);report.put("threads",threads);report.put("queueCapacity",queue);report.put("parentTasks",parents);
   report.put("segmentsPerParent",count);report.put("requestIntervalMs",interval);report.put("elapsedMs",elapsed);
   report.put("succeeded",done.get());report.put("modelFailures",errors.get());report.put("failedParents",failedParents.get());
   report.put("convergedFailedParents",converged.get());report.put("peakModelCalls",peak.get());report.put("peakQueueSampled",maxQueued.get());
   report.put("saturatedBatches",executor.stats().saturatedBatches());report.put("throughputSegmentsPerSec",1000.0*done.get()/elapsed);
   report.put("readyToWorkerP50Ms",percentile(waits,.5));report.put("readyToWorkerP95Ms",percentile(waits,.95));
   report.put("modelP95Ms",percentile(latencies,.95));report.put("parentDurationsMs",durations);
   report.put("minRequestGapMs",minGap==Long.MAX_VALUE?0:TimeUnit.NANOSECONDS.toMillis(minGap));
   return report;
  } finally {gate.countDown();callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
 }
 private long percentile(List<Long> values,double percentile) {
  if(values.isEmpty())return 0;var sorted=values.stream().sorted().toList();return sorted.get((int)Math.ceil(sorted.size()*percentile)-1);
 }
}
