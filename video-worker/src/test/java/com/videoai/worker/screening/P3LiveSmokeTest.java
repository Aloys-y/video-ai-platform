package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.worker.config.DashScopeConfig;
import com.videoai.worker.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用：复用P0转写，仅首轮调用文本模型；响应持久化后重跑不再计费。 */
@EnabledIfEnvironmentVariable(named="P3_LIVE_SMOKE",matches="true")
class P3LiveSmokeTest {
    @Test void screensRealTranscriptAndSavesOriginalResponse() throws Exception {
        Path repo=Files.isDirectory(Path.of("sql"))?Path.of("."):Path.of(".."), logs=repo.resolve("logs");Files.createDirectories(logs);
        ObjectMapper json=new ObjectMapper();TextAnalysisProperties config=new TextAnalysisProperties();
        config.setApiKey(System.getenv("TEXT_API_KEY"));var planner=new CandidatePlanner(config,new MediaProperties(),json);
        String sampleId=System.getenv().getOrDefault("P3_SAMPLE","dev-03-fun");
        if(!Set.of("dev-01-fun","dev-02-fun","dev-03-fun").contains(sampleId)) throw new IllegalArgumentException("未知P0样本");
        Path sample=repo.resolve("architecture/asr-p0/results/"+sampleId);
        List<TranscriptUtterance> rows=new ArrayList<>();
        for(JsonNode row:json.readTree(sample.resolve("transcript.json").toFile()).path("utterances"))
            rows.add(new TranscriptUtterance(row.path("id").asText(),row.path("start_ms").asLong(),row.path("end_ms").asLong(),row.path("text").asText(),0));
        long duration=json.readTree(sample.resolve("media.json").toFile()).path("duration_ms").asLong();
        var windows=planner.windows(new AudioPrefilterPreparationService.TranscriptManifest(1,duration,"TRANSCRIBED",rows));
        var client=new DashScopeTextClient(config,new DashScopeConfig(),json);List<CandidateRange> candidates=new ArrayList<>();
        List<JsonNode> usages=new ArrayList<>();int newCalls=0;
        planner.estimate(duration,windows,0);
        for(int i=0;i<windows.size();i++) {
            Path state=logs.resolve("p3-live-baseline-"+sampleId+"-batch-"+i+".request.json"),rawFile=logs.resolve("p3-live-baseline-"+sampleId+"-batch-"+i+".response.json");
            String hash=TextScreeningService.hash(json.writeValueAsString(config.snapshot())+windows.get(i).input());
            String raw;
            if(Files.exists(state)) {
                assertEquals(hash,json.readTree(state.toFile()).path("requestHash").asText(),"实测参数变化，需新建验证记录");
                assertTrue(Files.exists(rawFile),"上次调用状态不明，禁止自动重提");raw=Files.readString(rawFile);
            } else {
                json.writeValue(state.toFile(),Map.of("requestHash",hash,"status","STARTED"));
                raw=client.complete(TextPrompts.SCREEN,windows.get(i).input());Files.writeString(rawFile,raw);newCalls++;
            }
            JsonNode response=json.readTree(raw);var choice=response.path("choices").path(0);
            assertEquals("stop",choice.path("finish_reason").asText());
            candidates.addAll(planner.parse(choice.path("message").path("content").asText(),windows.get(i)));
            if(response.has("usage")) usages.add(response.get("usage"));
        }
        var plan=planner.plan(candidates,duration,windows);
        json.writeValue(logs.resolve("p3-live-smoke.json").toFile(),Map.of("sampleId",sampleId,"durationMs",duration,"batchCount",windows.size(),
                "candidateCount",plan.candidates().size(),"ranges",plan.ranges(),"usage",usages,"newCallsThisRun",newCalls,
                "estimatedTaskCny",plan.estimatedTaskCny(),"note","真实候选，未重新人工标注，不能作为准确率结论"));
        assertFalse(rows.isEmpty());
    }
}
