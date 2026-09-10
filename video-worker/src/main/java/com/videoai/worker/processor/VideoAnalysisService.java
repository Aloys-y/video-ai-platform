package com.videoai.worker.processor;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.ExecutionBudget;
import com.videoai.common.analysis.SegmentGuidance;
import com.videoai.common.domain.*;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.rag.service.RagOrchestrator;
import com.videoai.worker.media.AudioPrefilterPreparationService;
import com.videoai.worker.screening.TextScreeningService;
import com.videoai.worker.segment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;

/** 单个视频业务线程同步编排；阶段内并行分析片段，领取与续租由数据库调度器负责。 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisService implements com.videoai.worker.scheduler.DatabaseTaskScheduler.Work {
    private final AudioPrefilterPreparationService preparation;
    private final TextScreeningService text;
    private final SegmentAnalysisService segments;
    private final SegmentModelSettings settings;
    private final AnalysisTaskMapper tasks;
    private final AnalysisTextCallMapper textCalls;
    private final AnalysisSegmentMapper segmentRows;
    private final RagOrchestrator rag;
    private final ObjectMapper json;

    public record Result(String markdown, Long tokensUsed, PromptEnvelope ragContext) {}
    @Override
    public com.videoai.worker.scheduler.DatabaseTaskScheduler.Outcome execute(com.videoai.worker.scheduler.DatabaseTaskScheduler.Context context) throws Exception {
        var lease=context.lease();
        try(var ownership=com.videoai.common.analysis.ExecutionOwnership.bind(context.token())) {
            context.check();
            var task=tasks.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnalysisTask>()
                    .eq(AnalysisTask::getTaskId,lease.taskId()));
            if(task==null || task.getAttemptNo()!=lease.attemptNo() || !"RUNNING".equals(task.getStatus()))
                throw new IllegalStateException("任务不属于当前执行");
            try {
                // 数据库存对象key，不持久化短期签名URL。
                var result=run(task,lease.attemptNo(),task.getVideoUrl(),Instant.MAX);
                context.check();
                if(tasks.storeOutput(task.getTaskId(),lease.attemptNo(),result.markdown(),result.tokensUsed())!=1)
                    throw new UnsettledTaskException("任务结果未保存");
                return com.videoai.worker.scheduler.DatabaseTaskScheduler.Outcome.succeeded();
            } catch(Exception e) {
                context.check();
                if(e instanceof org.springframework.dao.DataAccessException || e instanceof org.springframework.transaction.TransactionException
                        || e instanceof UnsettledTaskException
                        || e instanceof SegmentAnalysisExecutor.BatchFailure batch && (!batch.converged() || batch.persistenceUnsettled())) throw e;
                if(e instanceof InterruptedException) {Thread.currentThread().interrupt();throw e;}
                long succeeded=segmentRows.selectExecution(task.getTaskId(),lease.attemptNo()).stream()
                        .filter(row->"SUCCEEDED".equals(row.getStatus())).count();
                segmentRows.failUnfinishedRunning(task.getTaskId(),lease.attemptNo());
                return new com.videoai.worker.scheduler.DatabaseTaskScheduler.Outcome(succeeded>0?"PARTIAL":"FAILED","ANALYSIS_FAILED");
            }
        }
    }


    Result run(AnalysisTask task, int no, String sourceKey, Instant deadline) throws Exception {
        String id = task.getTaskId();
        if (!json.valueToTree(settings.snapshot()).path("model").path("supported").asBoolean())
            throw new IOException("当前视频 Provider 尚未支持粗筛模式");
        try (var budget = ExecutionBudget.bind(deadline, () -> tasks.isCurrentProcessing(id, no) != 1)) {
            progress(id, no, "PREPARING_AUDIO", 5);
            AudioPrefilterPreparationService.TranscriptManifest transcript;
            AudioPrefilterPreparationService.SegmentManifest manifest;
            boolean noCandidates;
            var usage = new TokenUsage();
            try (var workspace = preparation.openWorkspace()) {
                transcript = preparation.prepareTranscript(id, no, sourceKey, workspace);
                progress(id, no, "SCREENING", 25);
                var screening = text.screen(id, no, workspace);
                noCandidates = screening.ranges().isEmpty();
                // 复用旧候选时不把旧批次计为本代次新调用。
                for (var batch : screening.batches()) {
                    var key = new AnalysisTextCall(); key.setTaskId(id); key.setExecutionNo(no); key.setPurpose("SCREEN"); key.setBatchNo(batch.batchNo());
                    var call = textCalls.select(key);
                    if (call != null && batch.responseObjectKey().equals(call.getResponseObjectKey())) usage.add(batch.usage());
                }
                progress(id, no, "PREPARING_SEGMENTS", 35);
                manifest = preparation.prepareSegments(id, no, sourceKey, screening.ranges(), () -> {
                    // 每个视频 rag 检索一次，实际参考冻结在 OSS 清单，重试不重新检索。
                    var envelope = rag.buildPrompt(task);
                    ExecutionBudget.check();
                    String context = envelope.getRetrievalContext() == null ? "" : envelope.getRetrievalContext();
                    return new SegmentGuidance(task.getPrompt(), context.substring(0, Math.min(context.length(), 12000)));
                }, workspace);
            }
            if (manifest == null) throw new IOException("片段清单缺失");
            if (noCandidates) {
                String reason = "NO_AUDIO".equals(transcript.outcome()) ? "视频没有可用音轨。"
                        : "NO_SPEECH".equals(transcript.outcome()) ? "未获得可用语音转写。" : "语音文字中未筛出交战候选。";
                return new Result("## 分析范围说明\n\n" + reason + "本次未进行视频画面分析，不能据此认定整局没有交战。", usage.total(), null);
            }
            if (manifest == null || manifest.guidance() == null
                    || !manifest.guidance().userPrompt().equals(task.getPrompt() == null ? "" : task.getPrompt().strip()))
                throw new IOException("冻结的片段参考与当前任务不匹配");
            progress(id, no, "ANALYZING_SEGMENTS", 45);
            var result = segments.analyze(id, no, deadline, p -> {
                try { progress(id, no, "ANALYZING_SEGMENTS", 45 + 50 * p.succeeded() / Math.max(1, p.expected())); }
                catch (IOException e) { throw new UnsettledTaskException("片段进度写入被拒绝"); }
            });
            for (var row : segmentRows.selectExecution(id, no)) {
                if (row.getReusedExecutionNo() == null)
                    usage.add(row.getUsageJson() == null ? null : json.readTree(row.getUsageJson()).get("reportedUsage"));
            }
            ExecutionBudget.check();
            // 仅保存完成说明，结果由片段查询接口读取；不再发起最终汇总模型调用。
            return new Result("已完成 " + result.segments().size() + " 个片段的分析，请查看各片段结果。仅覆盖筛选区间。", usage.total(), null);
        }
    }

    private void progress(String id, int no, String step, int progress) throws IOException {
        ExecutionBudget.check();
        if (tasks.updateStep(id, no, step) != 1)
            throw new IOException("父任务已结束或执行代次失效");
    }

    /** 仅汇总厂商已报告的本执行文本/视频 tokens；未知保持 null，不冒充费用账单。 */
    static final class TokenUsage {
        long sum; boolean unknown;
        void add(JsonNode usage) {
            JsonNode total = usage == null ? null : usage.get("total_tokens");
            if (total == null || !total.isIntegralNumber() || !total.canConvertToLong() || total.asLong() < 0) unknown = true;
            else sum = Math.addExact(sum, total.asLong());
        }
        Long total() { return unknown ? null : sum; }
    }
}
