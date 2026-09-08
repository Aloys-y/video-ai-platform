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
@EnabledIfSystemProperty(named="segment.elastic.load",matches="true")
class SegmentElasticLoadTest {
 @Test @Timeout(value=12,unit=TimeUnit.MINUTES)
 void matrix() throws Exception {
  var reports=new ArrayList<Map<String,Object>>();var json=new ObjectMapper();
  Path out=Path.of(System.getProperty("segment.elastic.output","target/elastic-load.json"));Files.createDirectories(out.toAbsolutePath().getParent());
  int[][] shapes=Arrays.stream(System.getProperty("segment.elastic.shapes","8/8/16,4/8/4,4/12/4").split(","))
          .map(v->Arrays.stream(v.split("/")).mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
  for(var shape:shapes) if(shape.length!=3) throw new IllegalArgumentException("配置必须为核心/最大/队列");
  for(int[] shape:shapes) for(int budget:new int[]{30,25}) for(int[] load:new int[][]{{1,6},{3,6},{3,12}}) {
   reports.add(run(load[0],load[1],budget,shape[0],shape[1],shape[2]));json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),reports);
   System.out.println("ELASTIC_LOAD completed="+reports.size()+"/"+(shapes.length*6)+" core="+shape[0]+" max="+shape[1]+" queue="+shape[2]+" parents="+load[0]+" segments="+load[1]+" budgetSeconds="+budget);
  }
 }
 private Map<String,Object> run(int parents,int count,int budgetSeconds,int core,int threads,int queue) throws Exception {
  var config=new SegmentAnalysisProperties();config.setThreads(core);config.setQueueCapacity(queue);config.setRequestIntervalMs(17);config.setCancellationGraceMs(34);
  var callers=Executors.newFixedThreadPool(parents);var gate=new CountDownLatch(1);
  var saved=ConcurrentHashMap.<String>newKeySet();var started=ConcurrentHashMap.<String>newKeySet();
  var active=new AtomicInteger();var peak=new AtomicInteger();var peakQueue=new AtomicInteger();var interrupted=new AtomicInteger();
  var rows=new CopyOnWriteArrayList<Map<String,Object>>();long began=System.nanoTime();
  try(var executor=new SegmentAnalysisExecutor(config,threads,1000)) {
   var futures=new ArrayList<Future<?>>();
   for(int p=0;p<parents;p++) {final int parent=p;
    futures.add(callers.submit(()->{
     gate.await();long ready=System.nanoTime();Instant deadline=Instant.now().plusSeconds(budgetSeconds);
     var firstCall=new AtomicLong(Long.MAX_VALUE);
     var admission=new long[count];var entered=new long[count];var requested=new long[count];
     var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
     for(int j=0;j<count;j++) {final int id=j;work.add(scope->{
      entered[id]=System.nanoTime();executor.awaitRequestPermit(scope);requested[id]=System.nanoTime();firstCall.accumulateAndGet(System.nanoTime(),Math::min);assertTrue(started.add(parent+"-"+id));peak.accumulateAndGet(active.incrementAndGet(),Math::max);
      try {
       Thread.sleep(new long[]{3000,7000,10000}[id%3]);
       scope.write(()->{assertTrue(saved.add(parent+"-"+id));return null;});return id;
      } catch(InterruptedException e) {interrupted.incrementAndGet();throw e;}
      finally {active.decrementAndGet();}
     });}
     var row=new LinkedHashMap<String,Object>();row.put("parent",parent);
     try {
      var result=executor.execute(work,deadline,()->false,v->{},(id,time)->admission[id]=time);
      assertEquals(count,result.size());row.put("status","COMPLETED");row.put("converged",true);
     } catch(SegmentAnalysisExecutor.BatchFailure e) {
      row.put("status","TIMED_OUT");row.put("reason",e.getMessage());row.put("converged",e.converged());
      var outcomes=new TreeMap<String,Integer>();for(var o:e.outcomes())outcomes.merge(o.status().name(),1,Integer::sum);
      row.put("outcomes",outcomes);assertEquals(count,e.outcomes().size());assertTrue(e.converged());
     }
     long successes=saved.stream().filter(id->id.startsWith(parent+"-")).count();
     row.put("firstModelStartMs",firstCall.get()==Long.MAX_VALUE?null:TimeUnit.NANOSECONDS.toMillis(firstCall.get()-ready));
     row.put("savedSegments",successes);row.put("totalSegments",count);
     var waitRows=new ArrayList<Map<String,Object>>();
     for(int id=0;id<count;id++) {
      var w=new LinkedHashMap<String,Object>();w.put("segment",id);
      w.put("admissionMs",admission[id]==0?null:TimeUnit.NANOSECONDS.toMillis(admission[id]-ready));
      w.put("queueMs",entered[id]==0||admission[id]==0?null:TimeUnit.NANOSECONDS.toMillis(Math.max(0,entered[id]-admission[id])));
      w.put("rateMs",requested[id]==0?null:TimeUnit.NANOSECONDS.toMillis(requested[id]-entered[id]));waitRows.add(w);
     }
     row.put("waits",waitRows);
     row.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-ready));rows.add(row);return null;
    }));
   }
   gate.countDown();
   while(futures.stream().anyMatch(f->!f.isDone())) {peakQueue.accumulateAndGet(executor.stats().queued(),Math::max);Thread.sleep(5);}
   for(var f:futures)f.get();
   long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
   assertEquals(0,active.get());assertEquals(0,executor.stats().queued());assertTrue(peak.get()<=threads);assertTrue(peakQueue.get()<=queue);
   int before=saved.size();Thread.sleep(100);assertEquals(before,saved.size(),"不得出现迟到模拟写入");
   long completed=rows.stream().filter(r->r.get("status").equals("COMPLETED")).count();
   if(parents==1)assertEquals(1,completed);if(parents*count==36 && threads==4)assertTrue(completed<parents);
   var r=new LinkedHashMap<String,Object>();r.put("core",core);r.put("maximum",threads);r.put("queue",queue);r.put("largestPoolSize",executor.largestPoolSizeForTest());r.put("parents",parents);r.put("segmentsPerParent",count);r.put("phaseBudgetSeconds",budgetSeconds);
   r.put("assumedPreprocessingMinutes",30-budgetSeconds);r.put("elapsedMs",elapsed);r.put("completedParents",completed);r.put("timedOutParents",parents-completed);
   r.put("savedSegments",saved.size());r.put("startedSegments",started.size());r.put("interruptedCalls",interrupted.get());r.put("peakCalls",peak.get());r.put("peakQueueSampled",peakQueue.get());
   r.put("parentResults",rows.stream().sorted(Comparator.comparingInt(x->(Integer)x.get("parent"))).toList());return r;
  } finally {gate.countDown();callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
 }
}
