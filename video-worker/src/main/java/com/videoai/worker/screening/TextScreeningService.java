package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.media.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** P3 编排：模型响应先落盘，严格解析后绑定不可变候选清单。 */
@Service
@RequiredArgsConstructor
public class TextScreeningService {
    private final TextAnalysisProperties config;
    private final CandidatePlanner planner;
    private final AiTextClient client;
    private final MediaPreparationService media;
    private final MediaProperties mediaConfig;
    private final StorageService storage;
    private final AnalysisExecutionMapper executions;
    private final AnalysisTaskMapper tasks;
    private final AnalysisTextCallMapper calls;
    private final ObjectMapper json;

    public record BatchReceipt(int batchNo,String responseObjectKey,JsonNode usage) {}
    public record ScreeningManifest(int schemaVersion,String outcome,String configHash,String transcriptHash,
                                    List<CandidateRange> candidates,List<AudioPrefilterPreparationService.Range> ranges,
                                    BigDecimal estimatedTaskCny,List<BatchReceipt> batches) {
        public ScreeningManifest { candidates=List.copyOf(candidates);ranges=List.copyOf(ranges);batches=List.copyOf(batches); }
    }
    public record SummaryResult(String markdown,String responseObjectKey,JsonNode usage) {}
    private record ResponseContent(String text,JsonNode usage,String objectKey) {}

    public ScreeningManifest screen(String taskId,int executionNo) throws IOException,InterruptedException {
        try(var ws=media.open()){return screen(taskId,executionNo,ws);}
    }
    public ScreeningManifest screen(String taskId,int executionNo,MediaPreparationService.Workspace ws) throws IOException,InterruptedException {
        active(taskId,executionNo,"SCREENING");
        AnalysisExecution execution=checkedExecution(taskId,executionNo);
        if(execution.getTranscriptObjectKey()==null) throw new IOException("尚未保存完整转写");
        {
            var transcript=readJson(ws,execution.getTranscriptObjectKey(),AudioPrefilterPreparationService.TranscriptManifest.class);
            String transcriptHash=hash(json.writeValueAsString(transcript));
            List<CandidatePlanner.Window> windows=planner.windows(transcript);
            planner.estimate(transcript.durationMs(),windows,0);
            if(execution.getCandidatesObjectKey()!=null) {
                ScreeningManifest saved=readJson(ws,execution.getCandidatesObjectKey(),ScreeningManifest.class);
                if(!saved.configHash().equals(execution.getConfigHash()) || !saved.transcriptHash().equals(transcriptHash))
                    throw new IOException("已有候选清单与执行配置/转写不一致");
                return saved;
            }
            List<CandidateRange> candidates=new ArrayList<>();List<BatchReceipt> receipts=new ArrayList<>();
            for(int index=0;index<windows.size();index++) {
                active(taskId,executionNo,"SCREENING");
                var window=windows.get(index);
                ResponseContent response=call(ws,execution,"SCREEN",index,TextPrompts.SCREEN,window.input());
                candidates.addAll(planner.parse(response.text(),window));
                if(candidates.size()>config.getMaxCandidates()) throw new IOException("候选总数量超出预算");
                receipts.add(new BatchReceipt(index,response.objectKey(),response.usage()));
            }
            var plan=planner.plan(candidates,transcript.durationMs(),windows);
            ScreeningManifest manifest=new ScreeningManifest(1,plan.ranges().isEmpty()?"NO_CANDIDATES":"CANDIDATES",
                    execution.getConfigHash(),transcriptHash,plan.candidates(),plan.ranges(),plan.estimatedTaskCny(),receipts);
            active(taskId,executionNo,"SCREENING");
            String key=writeRaw(ws,json.writeValueAsString(manifest));
            if(executions.bindOnce(taskId,executionNo,AnalysisExecutionMapper.Artifact.CANDIDATES,key)!=1)
                throw new IOException("候选清单保存冲突或执行已失效");
            return manifest;
        }
    }

    /** 接收已验证的视频结果和调用方提供的知识上下文；本服务不在粗筛阶段调用 RAG。 */
    public SummaryResult summarize(String taskId,int executionNo,List<SegmentReview> reviews,String knowledgeContext,String userPrompt)
            throws IOException,InterruptedException {
        active(taskId,executionNo,"SUMMARIZING");
        AnalysisExecution execution=checkedExecution(taskId,executionNo);
        if(execution.getSegmentsObjectKey()==null) throw new IOException("必须先冻结片段清单");
        try(var ws=media.open()) {
            var segments=readJson(ws,execution.getSegmentsObjectKey(),AudioPrefilterPreparationService.SegmentManifest.class).segments();
            Map<Integer,SegmentReview> byNo=new HashMap<>();
            for(var review:reviews) if(byNo.put(review.segmentNo(),review)!=null) throw new IOException("重复的片段分析结果");
            if(segments.size()!=reviews.size()) throw new IOException("片段结果未全部收齐，不能汇总");
            List<SegmentReview> ordered=new ArrayList<>();
            for(var segment:segments) {
                var review=byNo.get(segment.segmentNo());
                if(review==null || review.startMs()!=segment.startMs() || review.endMs()!=segment.endMs())
                    throw new IOException("汇总结果与冻结片段不匹配");
                ordered.add(review);
            }
            ordered.sort(Comparator.comparingLong(SegmentReview::startMs));
            String input=json.writeValueAsString(Map.of("segments",ordered,"knowledgeContext",knowledgeContext==null?"":knowledgeContext,
                    "userPrompt",userPrompt==null?"":userPrompt));
            if(CandidatePlanner.inputBytes(TextPrompts.SUMMARY,input)>config.getMaxRequestBytes())
                throw new IOException("汇总输入超过预算，需要分层汇总，不能静默截断片段");
            var response=call(ws,execution,"SUMMARY",0,TextPrompts.SUMMARY,input);
            active(taskId,executionNo,"SUMMARIZING");
            return new SummaryResult(response.text(),response.objectKey(),response.usage());
        }
    }

    private ResponseContent call(MediaPreparationService.Workspace ws,AnalysisExecution execution,String purpose,int batch,
                                 String system,String input) throws IOException {
        AnalysisTextCall key=new AnalysisTextCall();key.setTaskId(execution.getTaskId());key.setExecutionNo(execution.getExecutionNo());
        key.setPurpose(purpose);key.setBatchNo(batch);
        key.setRequestHash(hash(json.writeValueAsString(List.of(execution.getConfigHash(),system,input))));
        AnalysisTextCall saved=calls.select(key);String raw;
        if(saved!=null) {
            if(!key.getRequestHash().equals(saved.getRequestHash())) throw new IOException("该批次输入已变化，需使用新执行代次");
            if(saved.getResponseObjectKey()==null) throw new IOException("文本调用结果未知，不自动重提；请核对原调用");
            key.setResponseObjectKey(saved.getResponseObjectKey());raw=readRaw(ws,saved.getResponseObjectKey());
        } else {
            if(calls.claim(key)!=1) throw new IOException("文本批次已存在或执行失效");
            raw=client.complete(system,input);
            key.setResponseObjectKey(writeRaw(ws,raw));
            if(calls.recordResponse(key)!=1) throw new IOException("原始文本响应保存冲突，请核对记录");
        }
        try {
            JsonNode response=json.readTree(raw);
            if(response==null) throw new IllegalArgumentException();
            JsonNode choice=response.path("choices").path(0);
            if(!choice.path("finish_reason").asText().equals("stop") || !choice.path("message").path("content").isTextual()
                    || choice.path("message").path("content").asText().isBlank()) throw new IllegalArgumentException();
            return new ResponseContent(choice.path("message").path("content").asText(),response.get("usage"),key.getResponseObjectKey());
        } catch(IOException|IllegalArgumentException e) { throw new CandidatePlanner.InvalidTextResultException("文本响应被截断或格式无效，原始响应已保存"); }
    }
    private AnalysisExecution checkedExecution(String taskId,int no) throws IOException {
        config.validate();AnalysisExecution execution=executions.selectExecution(taskId,no);
        if(execution==null) throw new IOException("执行快照不存在");
        JsonNode snapshot=json.readTree(execution.getConfigSnapshot());
        if(!snapshot.path("settings").path("text").equals(json.readTree(json.writeValueAsBytes(config.snapshot()))))
            throw new IOException("执行快照没有匹配的P3文本配置，需使用新执行代次");
        return execution;
    }
    private void active(String taskId,int no,String step) throws IOException {
        ExecutionBudget.check();
        if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("文本处理已中断");
        if(tasks.updateStep(taskId,no,step)!=1) throw new IOException("任务已取消、结束或代次已变化");
    }
    private String writeRaw(MediaPreparationService.Workspace ws,String raw) throws IOException {
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16*1024*1024) throw new IOException("文本产物超过16MiB限制");
        Path file=ws.file("text-"+UUID.randomUUID()+".json");Files.write(file,bytes);ws.checkBudget();
        String key=storage.putArtifact(file,"application/json");Files.delete(file);return key;
    }
    private String readRaw(MediaPreparationService.Workspace ws,String key) throws IOException {
        Path file=ws.file("read-"+UUID.randomUUID()+".json");
        storage.downloadToFile(key,file,16*1024*1024,Duration.ofSeconds(60),mediaConfig.getMinFreeBytes());
        String raw=Files.readString(file);Files.delete(file);return raw;
    }
    private <T>T readJson(MediaPreparationService.Workspace ws,String key,Class<T> type) throws IOException {return json.readValue(readRaw(ws,key),type);}
    public static String hash(String content) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
