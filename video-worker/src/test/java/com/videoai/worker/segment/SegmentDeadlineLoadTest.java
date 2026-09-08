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

/** 1:60时间缩放，真实执行器；AI可中断等待，写入仅为内存模拟。 */
@EnabledIfSystemProperty(named="segment.deadline.load",matches="true")
class SegmentDeadlineLoadTest {
 @Test @Timeout(value=12,unit=TimeUnit.MINUTES)
 void matrix() throws Exception {
  var reports=new ArrayList<Map<String,Object>>();var json=new ObjectMapper();
  Path out=Path.of(System.getProperty("segment.deadline.output","target/deadline-load.json"));Files.createDirectories(out.toAbsolutePath().getParent());
  int[] threadCounts=Arrays.stream(System.getProperty("segment.deadline.threads","4").split(",")).mapToInt(Integer::parseInt).toArray();
  for(int threads:threadCounts) for(int budget:new int[]{30,25}) for(int[] load:new int[][]{{1,6},{3,6},{3,12}}) {
   reports.add(run(load[0],load[1],budget,threads));json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),reports);
   System.out.println("DEADLINE_LOAD completed="+reports.size()+"/"+(6*threadCounts.length)+" threads="+threads+" parents="+load[0]+" segments="+load[1]+" budgetSeconds="+budget);
  }
 }
 private Map<String,Object> run(int parents,int count,int budgetSeconds,int threads) throws Exception {
  var config=new SegmentAnalysisProperties();config.setThreads(threads);config.setRequestIntervalMs(17);config.setCancellationGraceMs(34);
  var callers=Executors.newFixedThreadPool(parents);var gate=new CountDownLatch(1);
  var saved=ConcurrentHashMap.<String>newKeySet();var started=ConcurrentHashMap.<String>newKeySet();
  var active=new AtomicInteger();var peak=new AtomicInteger();var peakQueue=new AtomicInteger();var interrupted=new AtomicInteger();
  var rows=new CopyOnWriteArrayList<Map<String,Object>>();long began=System.nanoTime();
  try(var executor=new SegmentAnalysisExecutor(config)) {
   var futures=new ArrayList<Future<?>>();
   for(int p=0;p<parents;p++) {final int parent=p;
    futures.add(callers.submit(()->{
     gate.await();long ready=System.nanoTime();Instant deadline=Instant.now().plusSeconds(budgetSeconds);
     var firstCall=new AtomicLong(Long.MAX_VALUE);
     var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
     for(int j=0;j<count;j++) {final int id=j;work.add(scope->{
      executor.awaitRequestPermit(scope);firstCall.accumulateAndGet(System.nanoTime(),Math::min);assertTrue(started.add(parent+"-"+id));peak.accumulateAndGet(active.incrementAndGet(),Math::max);
      try {
       Thread.sleep(new long[]{3000,7000,10000}[id%3]);
       scope.write(()->{assertTrue(saved.add(parent+"-"+id));return null;});return id;
      } catch(InterruptedException e) {interrupted.incrementAndGet();throw e;}
      finally {active.decrementAndGet();}
     });}
     var row=new LinkedHashMap<String,Object>();row.put("parent",parent);
     try {
      var result=executor.execute(work,deadline,()->false,v->{});
      assertEquals(count,result.size());row.put("status","COMPLETED");row.put("converged",true);
     } catch(SegmentAnalysisExecutor.BatchFailure e) {
      row.put("status","TIMED_OUT");row.put("reason",e.getMessage());row.put("converged",e.converged());
      var outcomes=new TreeMap<String,Integer>();for(var o:e.outcomes())outcomes.merge(o.status().name(),1,Integer::sum);
      row.put("outcomes",outcomes);assertEquals(count,e.outcomes().size());assertTrue(e.converged());
     }
     long successes=saved.stream().filter(id->id.startsWith(parent+"-")).count();
     row.put("firstModelStartMs",firstCall.get()==Long.MAX_VALUE?null:TimeUnit.NANOSECONDS.toMillis(firstCall.get()-ready));
     row.put("savedSegments",successes);row.put("totalSegments",count);
     row.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-ready));rows.add(row);return null;
    }));
   }
   gate.countDown();
   while(futures.stream().anyMatch(f->!f.isDone())) {peakQueue.accumulateAndGet(executor.stats().queued(),Math::max);Thread.sleep(5);}
   for(var f:futures)f.get();
   long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
   assertEquals(0,active.get());assertEquals(0,executor.stats().queued());assertTrue(peak.get()<=threads);assertTrue(peakQueue.get()<=16);
   int before=saved.size();Thread.sleep(100);assertEquals(before,saved.size(),"不得出现迟到模拟写入");
   long completed=rows.stream().filter(r->r.get("status").equals("COMPLETED")).count();
   if(parents==1)assertEquals(1,completed);if(parents*count==36 && threads==4)assertTrue(completed<parents);
   var r=new LinkedHashMap<String,Object>();r.put("threads",threads);r.put("parents",parents);r.put("segmentsPerParent",count);r.put("phaseBudgetSeconds",budgetSeconds);
   r.put("assumedPreprocessingMinutes",30-budgetSeconds);r.put("elapsedMs",elapsed);r.put("completedParents",completed);r.put("timedOutParents",parents-completed);
   r.put("savedSegments",saved.size());r.put("startedSegments",started.size());r.put("interruptedCalls",interrupted.get());r.put("peakCalls",peak.get());r.put("peakQueueSampled",peakQueue.get());
   r.put("parentResults",rows.stream().sorted(Comparator.comparingInt(x->(Integer)x.get("parent"))).toList());return r;
  } finally {gate.countDown();callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
 }
}
