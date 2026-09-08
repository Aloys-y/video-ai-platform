package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.worker.media.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidatePlannerTest {
    private final TextAnalysisProperties config=new TextAnalysisProperties();
    private final MediaProperties media=new MediaProperties();
    private final CandidatePlanner planner=new CandidatePlanner(config,media,new ObjectMapper());
    private AudioPrefilterPreparationService.TranscriptManifest transcript(List<TranscriptUtterance> rows,long duration) {
        return new AudioPrefilterPreparationService.TranscriptManifest(1,duration,"TRANSCRIBED",rows);
    }
    @Test void screeningPromptMatchesUserAcceptedP0Baseline() throws Exception {
        var root=java.nio.file.Files.isDirectory(java.nio.file.Path.of("sql"))?java.nio.file.Path.of("."):java.nio.file.Path.of("..");
        var baseline=new ObjectMapper().readTree(root.resolve("architecture/asr-p0/accepted-baseline.json").toFile());
        assertEquals(baseline.path("prompt_hash").asText(),TextScreeningService.hash(TextPrompts.SCREEN));
    }
    @Test void overlappingWindowsCoverEveryIdWithoutRedundantTail() throws Exception {
        List<TranscriptUtterance> rows=new ArrayList<>();
        for(int i=0;i<161;i++) rows.add(new TranscriptUtterance("u"+i,i*1000L,i*1000L+500,"前面有人",0));
        var windows=planner.windows(transcript(rows,200000));
        assertEquals(3,windows.size());assertEquals("u70",windows.get(1).utterances().get(0).id());
        assertEquals("u140",windows.get(2).utterances().get(0).id());
        assertEquals(161,windows.stream().flatMap(w->w.utterances().stream()).map(TranscriptUtterance::id).distinct().count());
    }
    @Test void idsMapToOriginalTimesThenExpandMergeAndSplit() throws Exception {
        var window=new CandidatePlanner.Window(List.of(new TranscriptUtterance("a",1000,2000,"接敌",0),
                new TranscriptUtterance("b",10000,12000,"打他",0)),"[]");
        var mapped=planner.parse("{\"candidates\":[{\"utterance_ids\":[\"a\",\"b\"],\"type\":\"engagement\",\"reason\":\"实时交战\"}]}",window);
        assertEquals(2,mapped.size());assertEquals(1000,mapped.get(0).startMs());
        media.setMaxSegmentMs(10000);
        var plan=planner.plan(mapped,100000,List.of(window));
        assertEquals(List.of(new AudioPrefilterPreparationService.Range(0,10000),new AudioPrefilterPreparationService.Range(10000,20000),
                new AudioPrefilterPreparationService.Range(20000,27000)),plan.ranges());
    }
    @Test void inventedIdsTimesMalformedAndDuplicateTranscriptIdsFail() throws Exception {
        var row=new TranscriptUtterance("a",0,1000,"x",0);var window=new CandidatePlanner.Window(List.of(row),"[]");
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->planner.parse("{\"candidates\":[{\"utterance_ids\":[\"missing\"],\"type\":\"contact\",\"reason\":\"x\"}]}",window));
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->planner.parse("{\"candidates\":[{\"startMs\":0}]}",window));
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->planner.parse(" ",window));
        assertThrows(IOException.class,()->planner.windows(transcript(List.of(row,row),2000)));
        assertThrows(IOException.class,()->planner.windows(transcript(List.of(row),500)));
    }
    @Test void zeroCandidatesAndBudgetFailuresNeverSilentlyDropIntervals() throws Exception {
        assertTrue(planner.windows(transcript(List.of(),1000)).isEmpty());
        assertTrue(planner.plan(List.of(),1000,List.of()).ranges().isEmpty());
        var c=new CandidateRange(100,1000,List.of("a"),CandidateRange.Type.ENGAGEMENT,"x");
        media.setMaxSelectedMs(500);
        assertThrows(IOException.class,()->planner.plan(List.of(c),2000,List.of()));
        config.setMaxEstimatedTaskCny(new BigDecimal("0.0001"));
        assertThrows(IOException.class,()->planner.estimate(1000,List.of(),0));
        var giant=new TranscriptUtterance("long",0,1000,"大".repeat(20000),0);
        assertThrows(IOException.class,()->planner.windows(transcript(List.of(giant),2000)));
    }
    @Test void selectsFirstEightAfterSplittingAndBudgetsOnlySelectedDuration() throws Exception {
        config.setBeforeMs(0);config.setAfterMs(0);config.setMergeGapMs(0);
        media.setMaxSegmentMs(1000);media.setMaxSelectedMs(8000);
        var candidate=new CandidateRange(0,9000,List.of("a"),CandidateRange.Type.ENGAGEMENT,"x");
        var result=planner.plan(List.of(candidate),10000,List.of());
        assertEquals(8,result.ranges().size());assertEquals(0,result.ranges().get(0).startMs());
        assertEquals(8000,result.ranges().get(7).endMs());assertEquals(List.of(candidate),result.candidates());
        assertEquals(planner.estimate(10000,List.of(),8000),result.estimatedTaskCny());
    }
    @Test void selectionIsChronologicalAndDoesNotPadShortVideos() throws Exception {
        config.setBeforeMs(0);config.setAfterMs(0);config.setMergeGapMs(0);
        List<CandidateRange> candidates=new ArrayList<>();
        for(int i=9;i>=0;i--) candidates.add(new CandidateRange(i*2000,i*2000+1000,List.of("u"+i),CandidateRange.Type.ENGAGEMENT,"x"));
        var result=planner.plan(candidates,20000,List.of());
        assertEquals(List.of(0L,2000L,4000L,6000L,8000L,10000L,12000L,14000L),result.ranges().stream().map(AudioPrefilterPreparationService.Range::startMs).toList());
        assertEquals(2,planner.plan(candidates.subList(0,2),20000,List.of()).ranges().size());
    }
    @Test void videoEvidenceConvertsRelativeTimeAndRejectsOutOfRange() throws Exception {
        var parser=new SegmentReviewParser(new ObjectMapper());var segment=new PreparedSegment(2,30000,35000,"clip.mp4");
        var review=parser.parse("{\"summary\":\"接敌\",\"events\":[{\"startMs\":500,\"endMs\":1500,\"observation\":\"出现敌人\"}],\"uncertainties\":[]}",segment);
        assertEquals(30500,review.events().get(0).startMs());
        assertThrows(CandidatePlanner.InvalidTextResultException.class,()->parser.parse("{\"summary\":\"x\",\"events\":[{\"startMs\":0,\"endMs\":6000,\"observation\":\"x\"}],\"uncertainties\":[]}",segment));
    }

    @Test void adviceMustHaveObservedEvidenceAndUsesOriginalTimeline() throws Exception {
        var parser=new SegmentReviewParser(new ObjectMapper()); var clip=new PreparedSegment(0,30000,35000,"clip");
        String valid="""
                {"summary":"接敌","events":[{"startMs":500,"endMs":1500,"observation":"离开掩体"}],
                "advice":[{"startMs":500,"endMs":1000,"issue":"暴露身位","suggestion":"利用掩体","knowledgeBasis":"掩体参考"}],"uncertainties":[]}
                """;
        var result=parser.parse(valid,clip);
        assertEquals(30500,result.advice().get(0).startMs()); assertEquals(31000,result.advice().get(0).endMs());
        assertTrue(parser.parse(valid.replace("\"endMs\":1000","\"endMs\":2000"),clip).advice().isEmpty());
        assertTrue(parser.parse(valid.replace("\"endMs\":1000","\"endMs\":6000"),clip).advice().isEmpty());
    }
}
