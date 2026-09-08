package com.videoai.worker.screening;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.PreparedSegment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SegmentReviewToleranceTest {
 private final ObjectMapper json=new ObjectMapper();
 private final SegmentReviewParser parser=new SegmentReviewParser(json);
 private final PreparedSegment clip=new PreparedSegment(0,300000,380000,"key");
 private String input(String events,String advice) {
  return "{\"summary\":\"观察\",\"events\":"+events+",\"advice\":"+advice+",\"uncertainties\":[]}";
 }
 private String event(int a,int b) { return "{\"startMs\":"+a+",\"endMs\":"+b+",\"observation\":\"交战\"}"; }
 private String advice() { return "[{\"startMs\":6000,\"endMs\":8000,\"issue\":\"暴露\",\"suggestion\":\"掩体\",\"knowledgeBasis\":\"\"}]"; }
 @Test void continuousEventsCoverAdviceButGapsDoNot() throws Exception {
  assertEquals(1,parser.parse(input("["+event(0,7000)+","+event(7000,10000)+"]",advice()),clip).advice().size());
  var result=parser.parse(input("["+event(0,7000)+","+event(7500,10000)+"]",advice()),clip);
  assertTrue(result.advice().isEmpty());assertEquals(2,result.events().size());assertTrue(result.uncertainties().get(0).contains("advice[0]"));
 }
 @Test void invalidAdviceRetainsObservationsAndReportsField() throws Exception {
  var result=parser.parse(input("["+event(0,10000)+"]",advice().replace("issue","observation")),clip);
  assertEquals(1,result.events().size());assertTrue(result.advice().isEmpty());assertTrue(result.uncertainties().get(0).contains("issue"));
 }
 @Test void suspiciousUnitsNeverBecomeClickableTimestamps() throws Exception {
  var result=parser.parse(input("["+event(2,18)+","+event(20,77)+"]","[]"),clip);
  assertTrue(result.events().isEmpty());assertTrue(result.advice().isEmpty());assertTrue(result.uncertainties().get(0).contains("时间单位待核对"));
 }
 @Test void brokenCoreReportsExactLocation() {
  var error=assertThrows(Exception.class,()->parser.parse(input("["+event(0,90000)+"]","[]"),clip));
  assertTrue(error.getMessage().contains("events[0]"));
 }
 @Test void replaySavedResponsesWhenExplicitlyRequested() throws Exception {
  String directory=System.getProperty("segment.replay.dir");
  org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null);
  long[] starts={302690,525980,732620},ends={390600,605980,776890};
  for(int i=0;i<3;i++) {
   var raw=json.readTree(java.nio.file.Path.of(directory,"p7-segment-"+i+"-response.json").toFile());
   var result=parser.parse(raw.path("text").asText(),new PreparedSegment(i,starts[i],ends[i],"key"));
   if(i==0) assertEquals(2,result.advice().size());
   if(i==1) { assertTrue(result.events().isEmpty());assertTrue(result.uncertainties().stream().anyMatch(s->s.contains("时间单位待核对"))); }
   if(i==2) assertEquals(2,result.advice().size());
   if(Boolean.getBoolean("segment.replay.export"))
    json.writeValue(java.nio.file.Path.of(directory,"p7-segment-"+i+"-reparsed.json").toFile(),result);
  }
 }
}
