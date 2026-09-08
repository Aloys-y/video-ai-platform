package com.videoai.worker.segment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
/** 真实分钟等待，显式启用；不访问AI、数据库或OSS。 */
@EnabledIfSystemProperty(named="segment.minutes",matches="true")
class SegmentPoolMinutesTest {
 @Test @Timeout(value=12,unit=TimeUnit.MINUTES)
 void realMinuteCallsWithOneFailure() throws Exception {
  var config=new SegmentAnalysisProperties();
  var output=Path.of(System.getProperty("segment.minutes.output","target/segment-minutes.json"));
  Files.createDirectories(output.toAbsolutePath().getParent());
  var events=new CopyOnWriteArrayList<Map<String,Object>>();var saved=new CopyOnWriteArrayList<Integer>();
  long began=System.nanoTime();var peak=new AtomicInteger();var active=new AtomicInteger();
  var json=new ObjectMapper();
  java.util.function.BiConsumer<String,Integer> record=(kind,id)->{
   events.add(Map.of("event",kind,"segment",id,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)));
   System.out.println("MINUTE_SIM "+kind+" segment="+id+" elapsedSeconds="+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began));
  };
  try(var executor=new SegmentAnalysisExecutor(config)) {
   var work=new ArrayList<SegmentAnalysisExecutor.Work<Integer>>();
   for(int minutes:new int[]{3,7,10}) work.add(scope->{
    executor.awaitRequestPermit(scope);peak.accumulateAndGet(active.incrementAndGet(),Math::max);record.accept("START",minutes);
    try {
     TimeUnit.MINUTES.sleep(minutes);
     if(minutes==7) {record.accept("FAIL",minutes);throw new java.io.IOException("模拟7分钟后AI调用失败");}
     scope.write(()->{saved.add(minutes);return null;});record.accept("SUCCESS",minutes);return minutes;
    } finally {active.decrementAndGet();}
   });
   var failure=assertThrows(SegmentAnalysisExecutor.BatchFailure.class,()->executor.execute(work,Instant.now().plusSeconds(660),()->false,id->record.accept("COLLECTED",id)));
   record.accept("PARENT_FAILED",0);
   assertTrue(failure.converged());assertEquals(Set.of(3,10),new HashSet<>(saved));
   assertEquals(2,failure.completed().size());assertEquals(0,active.get());assertEquals(0,executor.stats().queued());assertEquals(3,peak.get());
   assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began)>=600);
   json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("events",events,"savedSegments",saved,"peakCalls",peak.get(),"converged",failure.converged(),"threads",config.getThreads(),"queue",config.getQueueCapacity(),"requestIntervalMs",config.getRequestIntervalMs()));
  }
 }
}
